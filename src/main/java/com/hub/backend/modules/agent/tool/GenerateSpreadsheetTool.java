package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTable;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableStyleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a native {@code .xlsx} workbook from a NEURA-like JSON spec and stores it for download.
 *
 * <p>Supports theme accent, column/header colors, per-cell style objects
 * ({@code { "v": ..., "bgColor": "#RRGGBB", ... }}), freeze panes, Excel Table (filter + row stripes),
 * column/cell formulas, totals row, and column width/align/wrap. Charts, validation and templates
 * remain out of scope (P4).</p>
 */
@Component
public class GenerateSpreadsheetTool implements AgentTool {

    public static final String TOOL_NAME = "generate_spreadsheet";

    /** NEURA financial default accent. */
    static final String DEFAULT_ACCENT = "#1E3A5F";
    static final String DEFAULT_HEADER_COLOR = "#FFFFFF";
    static final String DEFAULT_FONT = "Calibri";

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_SHEETS = 20;
    private static final int MAX_COLUMNS = 50;
    private static final int MAX_ROWS = 5000;
    private static final String DEFAULT_TABLE_STYLE = "TableStyleMedium2";
    /** NEURA-aligned placeholders: {@code [{key}]} or {@code {key}} → A1 for the current row. */
    private static final Pattern FORMULA_BRACKET_KEY = Pattern.compile("\\[\\{([A-Za-z0-9_]+)\\}\\]");
    private static final Pattern FORMULA_BRACE_KEY = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");
    private static final Logger log = LoggerFactory.getLogger(GenerateSpreadsheetTool.class);

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    public GenerateSpreadsheetTool(FileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmToolDefinition definition() {
        Map<String, Object> columnProps = new LinkedHashMap<>();
        columnProps.put("key", Map.of("type", "string"));
        columnProps.put("header", Map.of("type", "string"));
        columnProps.put("format", Map.of(
                "type", "string",
                "enum", List.of("text", "number", "currency", "percent", "integer", "date"),
                "description", "单元格数值格式，默认 text"));
        columnProps.put("headerBg", Map.of(
                "type", "string",
                "description", "表头背景色 #RRGGBB；未写时默认 theme.accent。"
                        + "若已写 headerColor 且未写 headerBg，则不铺底色。"
                        + "只要字色、不要底色也可显式填 none / transparent"));
        columnProps.put("headerColor", Map.of(
                "type", "string",
                "description", "表头文字色 #RRGGBB；「红字」写这里（如 #FF0000），不是 headerBg；"
                        + "单独写字色时不会自动铺 accent 底；默认 #FFFFFF"));
        columnProps.put("headerBold", Map.of(
                "type", "boolean",
                "description", "表头是否加粗；「粗体红字」时为 true，默认 true"));
        columnProps.put("bgColor", Map.of(
                "type", "string",
                "description", "该列数据区默认背景色 #RRGGBB；仅当用户明确要求背景/底色时使用"));
        columnProps.put("fontColor", Map.of(
                "type", "string",
                "description", "该列数据区默认文字色 #RRGGBB；「名字粉色/粉色字」写这里（如 #FF69B4），不是 bgColor"));
        columnProps.put("bold", Map.of(
                "type", "boolean",
                "description", "该列数据区默认是否加粗"));
        columnProps.put("formula", Map.of(
                "type", "string",
                "description", "该列每行的 Excel 公式模板（对齐 NEURA）。"
                        + "可用 {key} 或 [{key}] 引用同行其他列，如 ={qty}*{price} 或 =SUM([{q1}]:[{q4}])；"
                        + "引擎展开为 A1 引用；也可用单元格值以 = 开头写单格公式。"
                        + "列 formula 优先于该格静态值。"));
        columnProps.put("width", Map.of(
                "type", "number",
                "description", "列宽（Excel 字符宽度，约 8–40）。写了则不再 autoSize，避免中文列被压扁或撑爆"));
        columnProps.put("align", Map.of(
                "type", "string",
                "enum", List.of("left", "center", "right"),
                "description", "数据区水平对齐；未写时 number/integer/currency/percent 默认 right"));
        columnProps.put("wrap", Map.of(
                "type", "boolean",
                "description", "数据区是否自动换行，默认 false；长文本列建议 true 并配合 width"));

        Map<String, Object> columnSchema = new LinkedHashMap<>();
        columnSchema.put("type", "object");
        columnSchema.put("properties", columnProps);
        columnSchema.put("required", List.of("key", "header"));

        Map<String, Object> tableProps = new LinkedHashMap<>();
        tableProps.put("columns", Map.of("type", "array", "items", columnSchema));
        tableProps.put("rows", Map.of(
                "type", "array",
                "items", Map.of("type", "object"),
                "description", "行对象；单元格可为标量，或 {v, bgColor, fontColor, bold} 覆盖单格样式；"
                        + "字符串以 = 开头时按公式写入"));
        tableProps.put("freeze", Map.of(
                "type", "string",
                "description", "冻结窗格，Excel 单元格引用，如 A2=冻首行、B2=冻首行+首列；true 等同 A2"));
        tableProps.put("excel_table", Map.of(
                "type", "boolean",
                "description", "是否建成 Excel Table（自动筛选 + 可选斑马纹），默认 false；工资/预算明细表建议 true"));
        tableProps.put("auto_filter", Map.of(
                "type", "boolean",
                "description", "自动筛选；未写时随 excel_table：建表则自带筛选，否则 false"));
        tableProps.put("zebra", Map.of(
                "type", "boolean",
                "description", "Excel Table 行斑马纹，仅 excel_table=true 时生效，默认 true"));
        tableProps.put("name", Map.of(
                "type", "string",
                "description", "Excel Table 显示名（可选），须工作簿内唯一"));
        tableProps.put("totals_row", Map.of(
                "type", "boolean",
                "description", "是否在数据下方追加合计行（对齐 NEURA：不在 Excel Table 内，写在表外下一行）。"
                        + "数值列（number/integer/currency）默认 =SUM；可用 totals 覆盖。默认 false"));
        tableProps.put("totals", Map.of(
                "type", "object",
                "description", "合计行配置。label=首列文案（默认「合计」）；"
                        + "其余键为列 key，值可为 sum/avg、以 = 开头的公式，或字面量。"
                        + "例：{\"label\":\"合计\",\"pay\":\"sum\",\"rate\":\"avg\"}"));

        Map<String, Object> tableSchema = new LinkedHashMap<>();
        tableSchema.put("type", "object");
        tableSchema.put("properties", tableProps);
        tableSchema.put("required", List.of("columns", "rows"));

        Map<String, Object> sheetProps = new LinkedHashMap<>();
        sheetProps.put("name", Map.of("type", "string", "description", "工作表名称"));
        sheetProps.put("freeze", Map.of(
                "type", "string",
                "description", "同 table.freeze；两者都写时以 table.freeze 为准"));
        sheetProps.put("table", tableSchema);

        Map<String, Object> sheetSchema = new LinkedHashMap<>();
        sheetSchema.put("type", "object");
        sheetSchema.put("properties", sheetProps);
        sheetSchema.put("required", List.of("name", "table"));

        Map<String, Object> themeProps = new LinkedHashMap<>();
        themeProps.put("accent", Map.of(
                "type", "string",
                "description", "主题强调色 #RRGGBB，用于默认表头背景与工作表标签，默认 #1E3A5F"));
        themeProps.put("font", Map.of(
                "type", "string",
                "description", "默认字体名，默认 Calibri"));

        Map<String, Object> themeSchema = new LinkedHashMap<>();
        themeSchema.put("type", "object");
        themeSchema.put("properties", themeProps);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string", "description", "工作簿标题，可用于默认文件名"));
        properties.put("filename", Map.of(
                "type", "string",
                "description", "文件名，可省略 .xlsx；未写时依次使用 title、第一个工作表名称"));
        properties.put("theme", themeSchema);
        properties.put("sheets", Map.of(
                "type", "array",
                "description", "一个或多个工作表，每表含 columns + rows。工资表建议按「工资明细 / 部门汇总 / 参数说明」命名；"
                        + "预算表建议按「预算明细 / 分类汇总 / 参数说明」命名；仅按实际内容创建需要的工作表",
                "items", sheetSchema));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("sheets"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "生成可下载的 Excel（.xlsx）文件。用户要求导出/下载表格、Excel、xlsx、统计表时调用；"
                        + "用 sheets[].table.columns 与 rows 提供表头与数据，不要只在聊天里贴表格。"
                        + "样式：headerColor/fontColor=文字色，headerBg/bgColor=背景色。"
                        + "「红字/粗体红字」→ headerColor+#FF0000 与 headerBold=true，不要写 headerBg；"
                        + "（只写 headerColor 时不会自动铺深蓝/accent 底）。"
                        + "「名字粉色」→ 姓名列 fontColor+#FF69B4，不要 bgColor；"
                        + "仅用户明确说底色/背景时才写 headerBg 或 bgColor。"
                        + "办公常用：冻首行写 freeze=A2；要筛选/斑马纹写 excel_table=true；"
                        + "行内计算列用 columns[].formula（如 ={qty}*{price}）；"
                        + "表尾合计写 totals_row=true（可选 totals 指定 sum/avg/公式）；"
                        + "长文本列建议 width + wrap=true；对齐可用 align=left/center/right。"
                        + "多页工资/预算工作簿按实际内容拆成明细、汇总、参数说明等 sheets，并使用清晰中文工作表名；"
                        + "填写 title 或 filename 以生成可读下载文件名。"
                        + "颜色用 #RRGGBB，必须写入工具参数，不要只在聊天里描述颜色。",
                schema);
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        JsonNode args;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            args = objectMapper.readTree(raw);
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed("工具参数不是合法 JSON：" + malformed.getOriginalMessage());
        }

        JsonNode sheetsNode = args.path("sheets");
        if (!sheetsNode.isArray() || sheetsNode.isEmpty()) {
            return ToolOutcome.failed("缺少 sheets 数组，请至少提供一个工作表");
        }
        if (sheetsNode.size() > MAX_SHEETS) {
            return ToolOutcome.failed("工作表数量不能超过 " + MAX_SHEETS);
        }

        String title = args.path("title").asText("").trim();
        String filename = args.path("filename").asText("").trim();
        if (filename.isBlank() && !title.isBlank()) {
            filename = title;
        }
        if (filename.isBlank()) {
            filename = sheetsNode.get(0).path("name").asText("").trim();
        }
        String safeName = GenerateMarkdownTool.sanitizeFilename(filename, "workbook.xlsx", "xlsx");
        ThemeSpec theme = ThemeSpec.from(args.path("theme"));

        WorkbookBuildResult built;
        try {
            built = buildWorkbook(sheetsNode, theme);
        } catch (IllegalArgumentException invalid) {
            return ToolOutcome.failed(invalid.getMessage());
        } catch (Exception failure) {
            log.warn("generate_spreadsheet build failed: {}", failure.getMessage());
            return ToolOutcome.failed("生成 Excel 失败，请稍后重试");
        }

        CurrentUser user = context.currentUser();
        try {
            UploadedFileResponse stored = fileService.storeGenerated(
                    user, built.bytes(), "xlsx", MIME, safeName,
                    FileAssetType.GENERATED, context.conversationId());
            Map<String, Object> fileMeta = GeneratedFileMeta.of(stored);
            String forModel = "已生成 Excel 文件「" + stored.originalName() + "」。"
                    + built.styleSummary()
                    + "请用一两句话告知用户通过下方下载卡片下载，并仅按上述样式摘要确认样式；"
                    + "不要输出 Markdown 链接或文件 URL（系统会自动展示下载卡片），"
                    + "不要声称文件中有摘要未列出的颜色或底色，不要在聊天里重复整表。";
            log.info("generate_spreadsheet fileId={} sheets={} user={}",
                    stored.id(), sheetsNode.size(), user.userId());
            return ToolOutcome.ok(forModel, Map.of("files", List.of(fileMeta)));
        } catch (Exception failure) {
            log.warn("generate_spreadsheet store failed: {}", failure.getMessage());
            return ToolOutcome.failed("保存 Excel 失败，请稍后重试");
        }
    }

    private static WorkbookBuildResult buildWorkbook(JsonNode sheetsNode, ThemeSpec theme) throws Exception {
        List<String> sheetSummaries = new ArrayList<>();
        Set<String> usedTableNames = new HashSet<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat dataFormat = workbook.createDataFormat();
            Map<String, XSSFCellStyle> styleCache = new LinkedHashMap<>();

            int sheetIndex = 0;
            for (JsonNode sheetNode : sheetsNode) {
                sheetIndex += 1;
                String sheetName = sheetNode.path("name").asText("").trim();
                if (sheetName.isBlank()) {
                    sheetName = "Sheet" + sheetIndex;
                }
                sheetName = sanitizeSheetName(sheetName, sheetIndex);

                JsonNode table = sheetNode.path("table");
                JsonNode columnsNode = table.path("columns");
                JsonNode rowsNode = table.path("rows");
                if (!columnsNode.isArray() || columnsNode.isEmpty()) {
                    throw new IllegalArgumentException("工作表「" + sheetName + "」缺少 columns");
                }
                if (!rowsNode.isArray()) {
                    throw new IllegalArgumentException("工作表「" + sheetName + "」的 rows 必须是数组");
                }
                if (columnsNode.size() > MAX_COLUMNS) {
                    throw new IllegalArgumentException("工作表「" + sheetName + "」列数不能超过 " + MAX_COLUMNS);
                }
                if (rowsNode.size() > MAX_ROWS) {
                    throw new IllegalArgumentException("工作表「" + sheetName + "」行数不能超过 " + MAX_ROWS);
                }

                List<ColumnSpec> columns = new ArrayList<>(columnsNode.size());
                Map<String, Integer> colIndexByKey = new LinkedHashMap<>();
                for (JsonNode columnNode : columnsNode) {
                    String key = columnNode.path("key").asText("").trim();
                    String header = columnNode.path("header").asText("").trim();
                    if (key.isBlank() || header.isBlank()) {
                        throw new IllegalArgumentException(
                                "工作表「" + sheetName + "」的 column 需要非空 key 与 header");
                    }
                    String format = columnNode.path("format").asText("text").trim().toLowerCase(Locale.ROOT);
                    columns.add(ColumnSpec.from(columnNode, key, header, format, theme));
                    colIndexByKey.put(key, columns.size() - 1);
                }

                TableOptions options = TableOptions.from(sheetNode, table);
                sheetSummaries.add(summarizeSheetStyles(sheetName, columns, options));

                XSSFSheet sheet = workbook.createSheet(sheetName);
                byte[] accentRgb = parseRgb(theme.accent());
                if (accentRgb != null) {
                    sheet.setTabColor(new XSSFColor(accentRgb, null));
                }

                Row headerRow = sheet.createRow(0);
                for (int col = 0; col < columns.size(); col++) {
                    ColumnSpec spec = columns.get(col);
                    Cell cell = headerRow.createCell(col);
                    cell.setCellValue(spec.header());
                    cell.setCellStyle(resolveStyle(
                            workbook, dataFormat, styleCache, theme.font(),
                            null, spec.headerBg(), spec.headerColor(), spec.headerBold(),
                            HorizontalAlignment.CENTER, false));
                }

                int rowIndex = 1;
                for (JsonNode rowNode : rowsNode) {
                    Row row = sheet.createRow(rowIndex);
                    int excelRow = rowIndex + 1; // POI 0-based → Excel 1-based
                    for (int col = 0; col < columns.size(); col++) {
                        ColumnSpec spec = columns.get(col);
                        Cell cell = row.createCell(col);
                        writeCell(cell, rowNode.path(spec.key()), spec, theme,
                                workbook, dataFormat, styleCache, excelRow, colIndexByKey);
                    }
                    rowIndex++;
                }

                // Excel Table covers HEADER + DATA only; totals sit as a normal row below (NEURA).
                int lastDataRow = Math.max(0, rowsNode.size()); // POI index; header=0
                int lastCol = columns.size() - 1;
                if (options.excelTable() && rowsNode.size() > 0) {
                    createExcelTable(workbook, sheet, sheetName, options.tableName(),
                            lastDataRow, lastCol, options.zebra(), usedTableNames);
                } else if (options.autoFilter() && rowsNode.size() > 0) {
                    sheet.setAutoFilter(new CellRangeAddress(0, lastDataRow, 0, lastCol));
                }
                if (options.totalsRow() && rowsNode.size() > 0) {
                    writeTotalsRow(sheet, columns, options, 1, lastDataRow,
                            theme, workbook, dataFormat, styleCache);
                }
                applyFreeze(sheet, options.freeze());

                for (int col = 0; col < columns.size(); col++) {
                    applyColumnWidth(sheet, col, columns.get(col));
                }
            }

            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("未生成任何工作表");
            }
            workbook.write(out);
            return new WorkbookBuildResult(out.toByteArray(), formatStyleSummary(sheetSummaries));
        }
    }

    /** Package-visible for unit tests of oral-style regression summaries. */
    static String formatStyleSummary(List<String> sheetSummaries) {
        if (sheetSummaries == null || sheetSummaries.isEmpty()) {
            return "已应用样式摘要：无额外列/表头颜色（使用默认）。";
        }
        return "已应用样式摘要：" + String.join("；", sheetSummaries) + "。";
    }

    private static String summarizeSheetStyles(String sheetName, List<ColumnSpec> columns, TableOptions options) {
        StringBuilder sb = new StringBuilder("工作表「").append(sheetName).append("」— ");
        boolean uniformHeader = columns.stream().allMatch(c ->
                sameColor(c.headerBg(), columns.get(0).headerBg())
                        && sameColor(c.headerColor(), columns.get(0).headerColor())
                        && c.headerBold() == columns.get(0).headerBold());
        if (uniformHeader) {
            sb.append("表头").append(describeHeaderStyle(columns.get(0)));
        } else {
            List<String> headerParts = new ArrayList<>();
            for (ColumnSpec col : columns) {
                headerParts.add("列「" + col.header() + "」表头" + describeHeaderStyle(col));
            }
            sb.append(String.join("，", headerParts));
        }

        List<String> dataParts = new ArrayList<>();
        for (ColumnSpec col : columns) {
            String dataStyle = describeDataStyle(col);
            if (dataStyle != null) {
                dataParts.add("列「" + col.header() + "」数据区" + dataStyle);
            }
        }
        if (!dataParts.isEmpty()) {
            sb.append("；").append(String.join("，", dataParts));
        }

        List<String> features = new ArrayList<>();
        if (options.freeze() != null && !options.freeze().isBlank()) {
            features.add("冻结" + options.freeze());
        }
        if (options.excelTable()) {
            features.add(options.zebra() ? "Excel Table（筛选+斑马纹）" : "Excel Table（筛选）");
        } else if (options.autoFilter()) {
            features.add("自动筛选");
        }
        List<String> formulaCols = new ArrayList<>();
        for (ColumnSpec col : columns) {
            if (col.formula() != null && !col.formula().isBlank()) {
                formulaCols.add(col.header());
            }
        }
        if (!formulaCols.isEmpty()) {
            features.add("公式列「" + String.join("」「", formulaCols) + "」");
        }
        if (options.totalsRow()) {
            features.add("合计行「" + options.totalsLabel() + "」");
        }
        if (!features.isEmpty()) {
            sb.append("；").append(String.join("、", features));
        }
        return sb.toString();
    }

    private static String describeHeaderStyle(ColumnSpec col) {
        StringBuilder part = new StringBuilder();
        if (col.headerBg() == null) {
            part.append("无底色");
        } else {
            part.append("底色").append(normalizeHex(col.headerBg()));
        }
        part.append("、字色").append(normalizeHex(col.headerColor()));
        if (col.headerBold()) {
            part.append("、加粗");
        }
        return part.toString();
    }

    private static String describeDataStyle(ColumnSpec col) {
        boolean hasBg = col.bgColor() != null && !col.bgColor().isBlank();
        boolean hasFont = col.fontColor() != null && !col.fontColor().isBlank();
        boolean hasBold = col.bold() != null;
        if (!hasBg && !hasFont && !hasBold) {
            return null;
        }
        StringBuilder part = new StringBuilder();
        if (hasBg) {
            part.append("底色").append(normalizeHex(col.bgColor()));
        } else {
            part.append("无底色");
        }
        if (hasFont) {
            part.append("、字色").append(normalizeHex(col.fontColor()));
        }
        if (Boolean.TRUE.equals(col.bold())) {
            part.append("、加粗");
        } else if (Boolean.FALSE.equals(col.bold())) {
            part.append("、不加粗");
        }
        return part.toString();
    }

    private static boolean sameColor(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return normalizeHex(left).equalsIgnoreCase(normalizeHex(right));
    }

    private static String normalizeHex(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String hex = raw.trim();
        if (!hex.startsWith("#")) {
            hex = "#" + hex;
        }
        return hex.toUpperCase(Locale.ROOT);
    }

    private record WorkbookBuildResult(byte[] bytes, String styleSummary) {
    }

    private static void writeCell(Cell cell, JsonNode rawValue, ColumnSpec spec, ThemeSpec theme,
                                  XSSFWorkbook workbook, DataFormat dataFormat,
                                  Map<String, XSSFCellStyle> styleCache,
                                  int excelRow, Map<String, Integer> colIndexByKey) {
        CellValue parsed = CellValue.parse(rawValue);
        JsonNode value = parsed.value();
        String bg = firstNonBlank(parsed.bgColor(), spec.bgColor());
        String fontColor = firstNonBlank(parsed.fontColor(), spec.fontColor());
        Boolean bold = parsed.bold() != null ? parsed.bold() : spec.bold();
        HorizontalAlignment align = resolveAlign(spec);
        boolean wrap = Boolean.TRUE.equals(spec.wrap());

        // Column-level formula always wins over static cell values (NEURA).
        if (spec.formula() != null && !spec.formula().isBlank()) {
            writeFormula(cell, expandColumnFormula(spec.formula(), excelRow, colIndexByKey));
            applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                    spec.format(), bg, fontColor, bold, align, wrap);
            return;
        }
        if (value != null && value.isTextual()) {
            String text = value.asText("");
            if (text.startsWith("=")) {
                writeFormula(cell, text);
                applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                        spec.format(), bg, fontColor, bold, align, wrap);
                return;
            }
        }

        if (value == null || value.isMissingNode() || value.isNull()) {
            cell.setBlank();
            applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                    spec.format(), bg, fontColor, bold, align, wrap);
            return;
        }
        if (value.isBoolean()) {
            cell.setCellValue(value.booleanValue());
            applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                    null, bg, fontColor, bold, align, wrap);
            return;
        }
        if (value.isNumber() || looksNumeric(spec.format())) {
            Double number = toNumber(value);
            if (number == null) {
                if (value.isTextual() && value.asText("").isBlank()) {
                    cell.setBlank();
                } else {
                    cell.setCellValue(value.asText());
                }
                applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                        null, bg, fontColor, bold, align, wrap);
                return;
            }
            cell.setCellValue(number);
            applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                    spec.format(), bg, fontColor, bold, align, wrap);
            return;
        }
        cell.setCellValue(value.asText());
        applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                null, bg, fontColor, bold, align, wrap);
    }

    /** Package-visible for unit tests of NEURA-style formula placeholders. */
    static String expandColumnFormula(String template, int excelRow, Map<String, Integer> colIndexByKey) {
        String out = template == null ? "" : template.trim();
        out = replaceFormulaKeys(FORMULA_BRACKET_KEY, out, excelRow, colIndexByKey);
        out = replaceFormulaKeys(FORMULA_BRACE_KEY, out, excelRow, colIndexByKey);
        if (!out.startsWith("=")) {
            out = "=" + out;
        }
        return out;
    }

    private static String replaceFormulaKeys(Pattern pattern, String input, int excelRow,
                                             Map<String, Integer> colIndexByKey) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Integer idx = colIndexByKey.get(key);
            String replacement = idx == null
                    ? matcher.group(0)
                    : CellReference.convertNumToColString(idx) + excelRow;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static void writeFormula(Cell cell, String formulaWithEquals) {
        String formula = formulaWithEquals == null ? "" : formulaWithEquals.trim();
        if (formula.startsWith("=")) {
            formula = formula.substring(1);
        }
        if (formula.isBlank()) {
            cell.setBlank();
            return;
        }
        cell.setCellFormula(formula);
    }

    /**
     * Writes a totals row below data (and below any Excel Table range).
     *
     * @param dataStartPoi first data row (usually 1)
     * @param dataEndPoi   last data row inclusive (POI 0-based)
     */
    private static void writeTotalsRow(XSSFSheet sheet, List<ColumnSpec> columns, TableOptions options,
                                       int dataStartPoi, int dataEndPoi, ThemeSpec theme,
                                       XSSFWorkbook workbook, DataFormat dataFormat,
                                       Map<String, XSSFCellStyle> styleCache) {
        int totalsPoi = dataEndPoi + 1;
        int excelDataStart = dataStartPoi + 1;
        int excelDataEnd = dataEndPoi + 1;
        Row row = sheet.createRow(totalsPoi);
        Map<String, String> totals = options.totalsByKey();

        for (int col = 0; col < columns.size(); col++) {
            ColumnSpec spec = columns.get(col);
            Cell cell = row.createCell(col);
            String key = spec.key();
            String letter = CellReference.convertNumToColString(col);
            boolean hasOverride = totals.containsKey(key);

            if (col == 0 && !hasOverride) {
                cell.setCellValue(options.totalsLabel());
                applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                        null, null, null, true, HorizontalAlignment.LEFT, false);
                continue;
            }

            HorizontalAlignment align = resolveAlign(spec);
            if (align == null) {
                align = HorizontalAlignment.RIGHT;
            }
            if (hasOverride) {
                String tval = totals.get(key);
                if (tval == null || tval.isBlank()) {
                    cell.setBlank();
                } else if (tval.startsWith("=")) {
                    writeFormula(cell, tval);
                } else if ("sum".equalsIgnoreCase(tval)) {
                    writeFormula(cell, "=SUM(" + letter + excelDataStart + ":" + letter + excelDataEnd + ")");
                } else if ("avg".equalsIgnoreCase(tval)
                        || "average".equalsIgnoreCase(tval)) {
                    writeFormula(cell, "=AVERAGE(" + letter + excelDataStart + ":"
                            + letter + excelDataEnd + ")");
                } else {
                    Double number = tryParseDouble(tval);
                    if (number != null) {
                        cell.setCellValue(number);
                    } else {
                        cell.setCellValue(tval);
                    }
                }
                applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                        looksNumeric(spec.format()) ? spec.format() : null, null, null, true, align, false);
                continue;
            }

            if (isSummableFormat(spec.format())) {
                writeFormula(cell, "=SUM(" + letter + excelDataStart + ":" + letter + excelDataEnd + ")");
                applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                        spec.format(), null, null, true, align, false);
            } else {
                cell.setBlank();
                applyStyleIfNeeded(cell, workbook, dataFormat, styleCache, theme.font(),
                        null, null, null, true, align, false);
            }
        }
    }

    private static void applyColumnWidth(XSSFSheet sheet, int col, ColumnSpec spec) {
        if (spec.width() != null && spec.width() > 0) {
            // POI column width unit = 1/256 of a character width
            int poiWidth = (int) Math.round(spec.width() * 256);
            poiWidth = Math.max(3 * 256, Math.min(255 * 256, poiWidth));
            sheet.setColumnWidth(col, poiWidth);
            return;
        }
        sheet.autoSizeColumn(col);
        int width = sheet.getColumnWidth(col);
        if (width < 3000) {
            sheet.setColumnWidth(col, 3000);
        } else if (width > 12000) {
            sheet.setColumnWidth(col, 12000);
        }
    }

    private static HorizontalAlignment resolveAlign(ColumnSpec spec) {
        HorizontalAlignment explicit = parseAlign(spec.align());
        if (explicit != null) {
            return explicit;
        }
        if (looksNumeric(spec.format())) {
            return HorizontalAlignment.RIGHT;
        }
        return null;
    }

    private static HorizontalAlignment parseAlign(String align) {
        if (align == null || align.isBlank()) {
            return null;
        }
        return switch (align.trim().toLowerCase(Locale.ROOT)) {
            case "left" -> HorizontalAlignment.LEFT;
            case "center" -> HorizontalAlignment.CENTER;
            case "right" -> HorizontalAlignment.RIGHT;
            default -> null;
        };
    }

    private static boolean isSummableFormat(String format) {
        return switch (format == null ? "" : format) {
            case "number", "integer", "currency" -> true;
            default -> false;
        };
    }

    private static Double tryParseDouble(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace(",", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void applyFreeze(XSSFSheet sheet, String freeze) {
        if (freeze == null || freeze.isBlank()) {
            return;
        }
        String ref = freeze.trim();
        if ("true".equalsIgnoreCase(ref) || "1".equals(ref) || "yes".equalsIgnoreCase(ref)) {
            sheet.createFreezePane(0, 1);
            return;
        }
        try {
            CellReference cell = new CellReference(ref);
            // freeze_panes "A2" → freeze rows above / cols left of that cell
            sheet.createFreezePane(Math.max(0, cell.getCol()), Math.max(0, cell.getRow()));
        } catch (Exception ignored) {
            // Invalid freeze ref: leave unfrozen rather than failing the whole workbook.
        }
    }

    private static void createExcelTable(XSSFWorkbook workbook, XSSFSheet sheet, String sheetName,
                                         String preferredName, int lastDataRow, int lastCol,
                                         boolean zebra, Set<String> usedTableNames) {
        AreaReference area = workbook.getCreationHelper().createAreaReference(
                new CellReference(0, 0),
                new CellReference(lastDataRow, lastCol));
        XSSFTable table = sheet.createTable(area);
        String displayName = uniqueTableName(preferredName, sheetName, usedTableNames);
        table.setName(displayName);
        table.setDisplayName(displayName);
        CTTable ctTable = table.getCTTable();
        CTTableStyleInfo styleInfo = ctTable.isSetTableStyleInfo()
                ? ctTable.getTableStyleInfo()
                : ctTable.addNewTableStyleInfo();
        styleInfo.setName(DEFAULT_TABLE_STYLE);
        styleInfo.setShowRowStripes(zebra);
        styleInfo.setShowColumnStripes(false);
        styleInfo.setShowFirstColumn(false);
        styleInfo.setShowLastColumn(false);
        if (!ctTable.isSetAutoFilter()) {
            ctTable.addNewAutoFilter().setRef(area.formatAsString());
        } else {
            ctTable.getAutoFilter().setRef(area.formatAsString());
        }
        table.updateHeaders();
    }

    private static String uniqueTableName(String preferred, String sheetName, Set<String> used) {
        String raw = preferred != null && !preferred.isBlank() ? preferred : sheetName + "Table";
        String safe = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("^_+|_+$", "");
        if (safe.isBlank()) {
            safe = "Table1";
        }
        if (Character.isDigit(safe.charAt(0))) {
            safe = "T_" + safe;
        }
        if (safe.length() > 240) {
            safe = safe.substring(0, 240);
        }
        String candidate = safe;
        int n = 2;
        while (used.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = safe + "_" + n;
            n++;
        }
        used.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private static void applyStyleIfNeeded(Cell cell, XSSFWorkbook workbook, DataFormat dataFormat,
                                           Map<String, XSSFCellStyle> styleCache, String fontName,
                                           String format, String bg, String fontColor, Boolean bold,
                                           HorizontalAlignment align, boolean wrap) {
        String pattern = excelFormatPattern(format);
        boolean hasVisual = (bg != null && !bg.isBlank())
                || (fontColor != null && !fontColor.isBlank())
                || Boolean.TRUE.equals(bold)
                || Boolean.FALSE.equals(bold)
                || align != null
                || wrap;
        if (pattern == null && !hasVisual) {
            return;
        }
        cell.setCellStyle(resolveStyle(
                workbook, dataFormat, styleCache, fontName, pattern, bg, fontColor, bold, align, wrap));
    }

    private static XSSFCellStyle resolveStyle(XSSFWorkbook workbook, DataFormat dataFormat,
                                              Map<String, XSSFCellStyle> cache, String fontName,
                                              String pattern, String bg, String fontColor, Boolean bold,
                                              HorizontalAlignment align, boolean wrap) {
        String key = String.join("|",
                pattern == null ? "" : pattern,
                blankToEmpty(bg),
                blankToEmpty(fontColor),
                bold == null ? "" : bold.toString(),
                blankToEmpty(fontName),
                align == null ? "" : align.name(),
                wrap ? "wrap" : "");
        XSSFCellStyle cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        XSSFCellStyle style = workbook.createCellStyle();
        if (pattern != null) {
            style.setDataFormat(dataFormat.getFormat(pattern));
        }

        byte[] bgRgb = parseRgb(bg);
        if (bgRgb != null) {
            style.setFillForegroundColor(new XSSFColor(bgRgb, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        if (align != null) {
            style.setAlignment(align);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
        }
        if (wrap) {
            style.setWrapText(true);
            if (align == null) {
                style.setVerticalAlignment(VerticalAlignment.CENTER);
            }
        }

        boolean needFont = (fontColor != null && !fontColor.isBlank())
                || bold != null
                || (fontName != null && !fontName.isBlank());
        if (needFont) {
            XSSFFont font = workbook.createFont();
            if (fontName != null && !fontName.isBlank()) {
                font.setFontName(fontName);
            }
            if (bold != null) {
                font.setBold(bold);
            }
            byte[] fontRgb = parseRgb(fontColor);
            if (fontRgb != null) {
                font.setColor(new XSSFColor(fontRgb, null));
            }
            style.setFont(font);
        }

        cache.put(key, style);
        return style;
    }

    private static Double toNumber(JsonNode value) {
        if (value.isNumber()) {
            return value.asDouble();
        }
        String text = value.asText("").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean looksNumeric(String format) {
        return switch (format) {
            case "number", "currency", "percent", "integer" -> true;
            default -> false;
        };
    }

    private static String excelFormatPattern(String format) {
        if (format == null) {
            return null;
        }
        return switch (format) {
            case "currency" -> "#,##0.00";
            case "percent" -> "0.00%";
            case "integer" -> "0";
            case "number" -> "#,##0.##";
            case "date" -> "yyyy-mm-dd";
            default -> null;
        };
    }

    static byte[] parseRgb(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6 || !hex.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return null;
        }
        int value = Integer.parseInt(hex, 16);
        return new byte[]{
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeSheetName(String raw, int index) {
        String name = raw.replaceAll("[\\\\/*\\[\\]:?]", "_").strip();
        if (name.isBlank()) {
            name = "Sheet" + index;
        }
        if (name.length() > 31) {
            name = name.substring(0, 31);
        }
        return name;
    }

    record ThemeSpec(String accent, String font) {
        static ThemeSpec from(JsonNode themeNode) {
            String accent = themeNode.path("accent").asText("").trim();
            if (parseRgb(accent) == null) {
                accent = DEFAULT_ACCENT;
            }
            String font = themeNode.path("font").asText("").trim();
            if (font.isBlank()) {
                font = DEFAULT_FONT;
            }
            return new ThemeSpec(accent, font);
        }
    }

    private record TableOptions(
            String freeze,
            boolean excelTable,
            boolean autoFilter,
            boolean zebra,
            String tableName,
            boolean totalsRow,
            String totalsLabel,
            Map<String, String> totalsByKey
    ) {
        static TableOptions from(JsonNode sheetNode, JsonNode tableNode) {
            String freeze = tableNode.path("freeze").asText("").trim();
            if (freeze.isBlank()) {
                freeze = sheetNode.path("freeze").asText("").trim();
            }
            if (freeze.isBlank()) {
                freeze = null;
            } else if ("true".equalsIgnoreCase(freeze) || "1".equals(freeze) || "yes".equalsIgnoreCase(freeze)) {
                freeze = "A2";
            } else {
                freeze = freeze.toUpperCase(Locale.ROOT);
            }

            boolean excelTable = tableNode.path("excel_table").asBoolean(false);
            boolean autoFilter = tableNode.has("auto_filter")
                    ? tableNode.path("auto_filter").asBoolean(false)
                    : excelTable;
            boolean zebra = !tableNode.has("zebra") || tableNode.path("zebra").asBoolean(true);
            String tableName = tableNode.path("name").asText("").trim();
            if (tableName.isBlank()) {
                tableName = tableNode.path("table_name").asText("").trim();
            }

            boolean totalsRow = tableNode.path("totals_row").asBoolean(false);
            String totalsLabel = "合计";
            Map<String, String> totalsByKey = new LinkedHashMap<>();
            JsonNode totalsNode = tableNode.path("totals");
            if (totalsNode.isObject()) {
                String label = totalsNode.path("label").asText("").trim();
                if (!label.isBlank()) {
                    totalsLabel = label;
                }
                var fields = totalsNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if ("label".equals(entry.getKey())) {
                        continue;
                    }
                    String raw = entry.getValue() == null || entry.getValue().isNull()
                            ? ""
                            : entry.getValue().asText("").trim();
                    totalsByKey.put(entry.getKey(), raw);
                }
            }
            return new TableOptions(freeze, excelTable, autoFilter, zebra,
                    tableName.isBlank() ? null : tableName,
                    totalsRow, totalsLabel, Map.copyOf(totalsByKey));
        }
    }

    private record ColumnSpec(
            String key,
            String header,
            String format,
            String headerBg,
            String headerColor,
            boolean headerBold,
            String bgColor,
            String fontColor,
            Boolean bold,
            String formula,
            Double width,
            String align,
            Boolean wrap
    ) {
        static ColumnSpec from(JsonNode node, String key, String header, String format, ThemeSpec theme) {
            String explicitHeaderColor = node.path("headerColor").asText("").trim();
            boolean hasHeaderColor = parseRgb(explicitHeaderColor) != null;
            String headerBg = resolveHeaderBg(node, theme, hasHeaderColor);
            String headerColor = explicitHeaderColor;
            if (!hasHeaderColor) {
                // No fill + white text is unreadable; fall back to near-black when headerBg cleared.
                headerColor = headerBg == null ? "#000000" : DEFAULT_HEADER_COLOR;
            }
            boolean headerBold = !node.has("headerBold") || node.path("headerBold").asBoolean(true);

            String bgColor = node.path("bgColor").asText("").trim();
            if (parseRgb(bgColor) == null) {
                bgColor = null;
            }
            String fontColor = node.path("fontColor").asText("").trim();
            if (parseRgb(fontColor) == null) {
                fontColor = null;
            }
            Boolean bold = node.has("bold") && !node.path("bold").isNull()
                    ? node.path("bold").asBoolean()
                    : null;
            String formula = node.path("formula").asText("").trim();
            if (formula.isBlank()) {
                formula = null;
            }
            Double width = null;
            if (node.has("width") && node.path("width").isNumber()) {
                double w = node.path("width").asDouble();
                if (w > 0) {
                    width = w;
                }
            }
            String align = node.path("align").asText("").trim().toLowerCase(Locale.ROOT);
            if (!List.of("left", "center", "right").contains(align)) {
                align = null;
            }
            Boolean wrap = node.has("wrap") && !node.path("wrap").isNull()
                    ? node.path("wrap").asBoolean()
                    : null;
            return new ColumnSpec(key, header, format, headerBg, headerColor, headerBold,
                    bgColor, fontColor, bold, formula, width, align, wrap);
        }

        /**
         * Explicit {@code none}/{@code transparent}/blank clears fill.
         * Missing {@code headerBg}: keep accent by default, but skip fill when {@code headerColor}
         * is set (「红字」without asking for a header background).
         */
        private static String resolveHeaderBg(JsonNode node, ThemeSpec theme, boolean hasHeaderColor) {
            if (!node.has("headerBg") || node.path("headerBg").isNull()) {
                return hasHeaderColor ? null : theme.accent();
            }
            String raw = node.path("headerBg").asText("").trim();
            if (raw.isEmpty()
                    || "none".equalsIgnoreCase(raw)
                    || "transparent".equalsIgnoreCase(raw)) {
                return null;
            }
            return parseRgb(raw) == null ? theme.accent() : raw;
        }
    }

    private record CellValue(JsonNode value, String bgColor, String fontColor, Boolean bold) {
        static CellValue parse(JsonNode raw) {
            if (raw != null && raw.isObject() && (raw.has("v") || raw.has("value"))) {
                JsonNode value = raw.has("v") ? raw.get("v") : raw.get("value");
                String bg = raw.path("bgColor").asText("").trim();
                String font = raw.path("fontColor").asText("").trim();
                Boolean bold = raw.has("bold") && !raw.path("bold").isNull()
                        ? raw.path("bold").asBoolean()
                        : null;
                return new CellValue(
                        value,
                        parseRgb(bg) == null ? null : bg,
                        parseRgb(font) == null ? null : font,
                        bold);
            }
            return new CellValue(raw, null, null, null);
        }
    }
}

package com.hub.backend.modules.agent.tool;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.data.style.BorderStyle;
import com.deepoove.poi.data.style.CellStyle;
import com.deepoove.poi.data.style.ParagraphStyle;
import com.deepoove.poi.data.style.RowStyle;
import com.deepoove.poi.data.style.Style;
import com.deepoove.poi.plugin.markdown.MarkdownRenderData;
import com.deepoove.poi.plugin.markdown.MarkdownRenderPolicy;
import com.deepoove.poi.plugin.markdown.MarkdownStyle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders Markdown into a native {@code .docx} and stores it for download.
 *
 * <p>Supports theme title/author/accent/template, optional YAML frontmatter, and
 * report-style header/footer with page numbers (P2 subset). Chat injects this tool
 * on the stream path; agents must enable ability {@code generate_docx} explicitly.</p>
 */
@Component
public class GenerateDocxTool implements AgentTool {

    public static final String TOOL_NAME = "generate_docx";

    /** Align with {@link GenerateSpreadsheetTool#DEFAULT_ACCENT}. */
    static final String DEFAULT_ACCENT = GenerateSpreadsheetTool.DEFAULT_ACCENT;
    static final String MUTED_INK = "7A7F8A";
    private static final String HORIZONTAL_RULE_MARKER = "DOCX_HORIZONTAL_RULE_7F3A9C";
    private static final int A4_WIDTH_TWIPS = 11906;
    private static final int A4_HEIGHT_TWIPS = 16838;

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final int MAX_CONTENT_CHARS = 500_000;
    private static final Logger log = LoggerFactory.getLogger(GenerateDocxTool.class);

    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final byte[] templateBytes;

    public GenerateDocxTool(FileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        this.templateBytes = buildMarkdownTemplate();
    }

    @Override
    public LlmToolDefinition definition() {
        Map<String, Object> themeProps = new LinkedHashMap<>();
        themeProps.put("accent", Map.of(
                "type", "string",
                "description", "主题强调色 #RRGGBB，用于表格表头与引用条，默认 #1E3A5F"));
        themeProps.put("title", Map.of(
                "type", "string",
                "description", "文档标题；用于页眉与默认文件名"));
        themeProps.put("author", Map.of(
                "type", "string",
                "description", "作者；可出现在页脚"));
        themeProps.put("template", Map.of(
                "type", "string",
                "enum", List.of("report", "blank", "letter"),
                "description", "版式模板：report=页眉标题+页脚页码（默认）；blank=无页眉页脚；letter=信函（默认无页码）"));
        themeProps.put("header", Map.of(
                "type", "boolean",
                "description", "是否显示页眉；未写时随 template"));
        themeProps.put("footer", Map.of(
                "type", "boolean",
                "description", "是否显示页脚；未写时随 template"));
        themeProps.put("page_numbers", Map.of(
                "type", "boolean",
                "description", "页脚是否含页码；未写时随 template（report 默认 true）"));

        Map<String, Object> themeSchema = new LinkedHashMap<>();
        themeSchema.put("type", "object");
        themeSchema.put("properties", themeProps);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filename", Map.of(
                "type", "string",
                "description", "文件名，可省略 .docx；未写时依次使用 theme.title、frontmatter title、正文首个 Markdown 标题"));
        properties.put("content", Map.of(
                "type", "string",
                "description", "完整 Markdown 正文；可含 YAML frontmatter（title/author/accent/template）"));
        properties.put("theme", themeSchema);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("content"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "生成可下载的 Word（.docx）文档。用户要求 Word、docx、可编辑文档、正式报告时调用；"
                        + "不要在用户只要 Markdown/.md 时调用本工具。"
                        + "把完整 Markdown 放进 content（可带 YAML frontmatter）；"
                        + "theme.title/author/accent/template 控制标题与版式；"
                        + "report 模板默认页眉标题、页脚页码、标题自动编号；Markdown 分隔线会渲染为 Word 横线。",
                schema);
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        String filename;
        String content;
        JsonNode themeNode;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            JsonNode args = objectMapper.readTree(raw);
            filename = args.path("filename").asText("").trim();
            content = args.path("content").asText("");
            themeNode = args.path("theme");
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed("工具参数不是合法 JSON：" + malformed.getOriginalMessage());
        }

        if (content.isBlank()) {
            return ToolOutcome.failed("缺少 content 参数，请给出 Markdown 正文");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return ToolOutcome.failed("文档正文过长，请控制在 " + MAX_CONTENT_CHARS + " 字符以内");
        }

        FrontMatter fm = parseFrontMatter(content);
        DocTheme theme = DocTheme.from(themeNode, fm.meta());
        String body = fm.body();
        if (body.isBlank()) {
            return ToolOutcome.failed("缺少可用正文：frontmatter 之后没有 Markdown 内容");
        }

        if (filename.isBlank() && theme.title() != null && !theme.title().isBlank()) {
            filename = theme.title();
        }
        if (filename.isBlank()) {
            filename = GenerateMarkdownTool.firstMarkdownHeading(body);
        }
        String safeName = GenerateMarkdownTool.sanitizeFilename(filename, "document.docx", "docx");
        CurrentUser user = context.currentUser();

        try {
            byte[] bytes = renderMarkdownToDocx(body, theme);
            UploadedFileResponse stored = fileService.storeGenerated(
                    user, bytes, "docx", MIME, safeName,
                    FileAssetType.GENERATED, context.conversationId());
            Map<String, Object> fileMeta = GeneratedFileMeta.of(stored);
            String forModel = "已生成 Word 文档「" + stored.originalName() + "」。"
                    + formatDocSummary(theme)
                    + "请用一两句话告知用户通过下方下载卡片下载；"
                    + "不要输出 Markdown 链接或文件 URL（系统会自动展示下载卡片），不要重复粘贴全文。"
                    + "回复版式时以本摘要为准。";
            log.info("generate_docx fileId={} template={} user={}",
                    stored.id(), theme.template(), user.userId());
            return ToolOutcome.ok(forModel, Map.of("files", List.of(fileMeta)));
        } catch (Exception failure) {
            log.warn("generate_docx failed: {}", failure.getMessage());
            return ToolOutcome.failed("生成 Word 文档失败，请稍后重试");
        }
    }

    private byte[] renderMarkdownToDocx(String markdown, DocTheme theme) throws Exception {
        String markedMarkdown = markHorizontalRules(markdown);
        boolean hasHorizontalRule = markedMarkdown.contains(HORIZONTAL_RULE_MARKER);
        MarkdownRenderData md = new MarkdownRenderData();
        md.setMarkdown(markedMarkdown);
        md.setStyle(buildMarkdownStyle(theme.accent(), "report".equals(theme.template())));

        Configure config = Configure.builder()
                .bind("md", new MarkdownRenderPolicy())
                .build();

        byte[] rendered;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             XWPFTemplate template = XWPFTemplate.compile(
                     new ByteArrayInputStream(templateBytes), config)) {
            template.render(Map.of("md", md));
            template.write(out);
            rendered = out.toByteArray();
        }

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(rendered));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            applyDocumentLayout(doc, theme);
            if (hasHorizontalRule) {
                applyHorizontalRules(doc, theme.accent());
            }
            applyHeaderFooter(doc, theme);
            doc.write(out);
            return out.toByteArray();
        }
    }

    static void applyDocumentLayout(XWPFDocument doc, DocTheme theme) {
        CTSectPr section = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        pageSize.setW(java.math.BigInteger.valueOf(A4_WIDTH_TWIPS));
        pageSize.setH(java.math.BigInteger.valueOf(A4_HEIGHT_TWIPS));

        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setTop(java.math.BigInteger.valueOf(1134));
        margins.setBottom(java.math.BigInteger.valueOf(1134));
        margins.setLeft(java.math.BigInteger.valueOf(1276));
        margins.setRight(java.math.BigInteger.valueOf(1276));
        margins.setHeader(java.math.BigInteger.valueOf(567));
        margins.setFooter(java.math.BigInteger.valueOf(567));

        boolean report = "report".equals(theme.template());
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            String style = paragraph.getStyle();
            if (report && "1".equals(style)) {
                paragraph.setSpacingBefore(0);
                paragraph.setSpacingAfter(220);
                paragraph.setKeepNext(true);
                for (XWPFRun run : paragraph.getRuns()) {
                    run.setFontSize(22);
                    run.setBold(true);
                    run.setColor("000000");
                }
            } else if (report && "2".equals(style)) {
                paragraph.setSpacingBefore(260);
                paragraph.setSpacingAfter(100);
                paragraph.setKeepNext(true);
            } else if (report && "3".equals(style)) {
                paragraph.setSpacingBefore(180);
                paragraph.setSpacingAfter(80);
                paragraph.setKeepNext(true);
            } else if (paragraph.getNumID() != null) {
                paragraph.setIndentationLeft(420);
                paragraph.setIndentationHanging(240);
                paragraph.setSpacingAfter(60);
            } else if (report && isPlainBodyParagraph(paragraph)) {
                paragraph.setSpacingAfter(100);
                paragraph.setSpacingBetween(1.15);
            }
        }
    }

    private static boolean isPlainBodyParagraph(XWPFParagraph paragraph) {
        CTPPr properties = paragraph.getCTP().getPPr();
        return paragraph.getStyle() == null
                && !paragraph.getText().isBlank()
                && (properties == null || (!properties.isSetPBdr() && !properties.isSetShd()));
    }

    static void applyHeaderFooter(XWPFDocument doc, DocTheme theme) {
        if (theme.header()) {
            String headerText = firstNonBlank(theme.title(), "文档");
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph paragraph = header.createParagraph();
            paragraph.setAlignment(ParagraphAlignment.LEFT);
            XWPFRun run = paragraph.createRun();
            run.setText(headerText);
            run.setFontSize(9);
            run.setColor(MUTED_INK);
            run.setFontFamily("Calibri");
        }
        boolean hasAuthor = theme.author() != null && !theme.author().isBlank();
        if (theme.footer() && (hasAuthor || theme.pageNumbers())) {
            XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
            XWPFParagraph paragraph = footer.createParagraph();
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            if (hasAuthor) {
                XWPFRun authorRun = paragraph.createRun();
                authorRun.setText(theme.author());
                authorRun.setFontSize(9);
                authorRun.setColor(MUTED_INK);
                authorRun.setFontFamily("Calibri");
            }
            if (theme.pageNumbers()) {
                if (hasAuthor) {
                    XWPFRun sep = paragraph.createRun();
                    sep.setText("  ·  ");
                    sep.setFontSize(9);
                    sep.setColor(MUTED_INK);
                }
                addPageNumberField(paragraph);
            }
        }
    }

    private static void addPageNumberField(XWPFParagraph paragraph) {
        XWPFRun begin = paragraph.createRun();
        begin.setFontSize(9);
        begin.setColor(MUTED_INK);
        CTFldChar beginChar = begin.getCTR().addNewFldChar();
        beginChar.setFldCharType(STFldCharType.BEGIN);

        XWPFRun instr = paragraph.createRun();
        instr.getCTR().addNewInstrText().setStringValue(" PAGE ");

        XWPFRun separate = paragraph.createRun();
        separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

        XWPFRun placeholder = paragraph.createRun();
        placeholder.setText("1");
        placeholder.setFontSize(9);
        placeholder.setColor(MUTED_INK);
        placeholder.setFontFamily("Calibri");

        XWPFRun end = paragraph.createRun();
        end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    static String formatDocSummary(DocTheme theme) {
        List<String> parts = new ArrayList<>();
        parts.add("模板 " + theme.template());
        parts.add("accent #" + normalizeHex(theme.accent()));
        if (theme.title() != null && !theme.title().isBlank()) {
            parts.add("标题「" + theme.title() + "」");
        }
        if (theme.author() != null && !theme.author().isBlank()) {
            parts.add("作者「" + theme.author() + "」");
        }
        if (theme.header()) {
            parts.add("有页眉");
        } else {
            parts.add("无页眉");
        }
        if (theme.footer() && theme.pageNumbers()) {
            parts.add("页脚含页码");
        } else if (theme.footer()) {
            parts.add("有页脚无页码");
        } else {
            parts.add("无页脚");
        }
        if ("report".equals(theme.template())) {
            parts.add("标题自动编号");
        }
        return "已应用版式摘要：" + String.join("；", parts) + "。";
    }

    private static String markHorizontalRules(String markdown) {
        return markdown.lines()
                .map(line -> isHorizontalRule(line) ? HORIZONTAL_RULE_MARKER : line)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static boolean isHorizontalRule(String line) {
        String compact = line == null ? "" : line.strip().replace(" ", "");
        return compact.length() >= 3
                && (compact.chars().allMatch(ch -> ch == '-')
                || compact.chars().allMatch(ch -> ch == '*')
                || compact.chars().allMatch(ch -> ch == '_'));
    }

    private static void applyHorizontalRules(XWPFDocument doc, String accent) {
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            if (!HORIZONTAL_RULE_MARKER.equals(paragraph.getText().strip())) {
                continue;
            }
            for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) {
                paragraph.removeRun(index);
            }
            CTPPr properties = paragraph.getCTP().isSetPPr()
                    ? paragraph.getCTP().getPPr()
                    : paragraph.getCTP().addNewPPr();
            CTPBdr borders = properties.isSetPBdr() ? properties.getPBdr() : properties.addNewPBdr();
            CTBorder bottom = borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom();
            bottom.setVal(STBorder.SINGLE);
            bottom.setColor(normalizeHex(accent));
            bottom.setSz(java.math.BigInteger.valueOf(8));
            bottom.setSpace(java.math.BigInteger.ONE);
        }
    }

    /** Package-visible for unit tests. */
    static FrontMatter parseFrontMatter(String content) {
        String raw = content == null ? "" : content;
        String trimmedStart = raw.stripLeading();
        if (!trimmedStart.startsWith("---")) {
            return new FrontMatter(raw, Map.of());
        }
        // Find closing --- on its own line after the opening fence.
        String afterOpen = trimmedStart.substring(3);
        if (afterOpen.startsWith("\r\n")) {
            afterOpen = afterOpen.substring(2);
        } else if (afterOpen.startsWith("\n")) {
            afterOpen = afterOpen.substring(1);
        }
        int closeIdx = indexOfClosingFence(afterOpen);
        if (closeIdx < 0) {
            return new FrontMatter(raw, Map.of());
        }
        String yaml = afterOpen.substring(0, closeIdx).trim();
        String body = afterOpen.substring(closeIdx + 3);
        if (body.startsWith("\r\n")) {
            body = body.substring(2);
        } else if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        return new FrontMatter(body, parseSimpleYaml(yaml));
    }

    private static int indexOfClosingFence(String text) {
        int from = 0;
        while (from <= text.length() - 3) {
            int idx = text.indexOf("---", from);
            if (idx < 0) {
                return -1;
            }
            boolean lineStart = idx == 0 || text.charAt(idx - 1) == '\n';
            int after = idx + 3;
            boolean lineEnd = after >= text.length()
                    || text.charAt(after) == '\n'
                    || (text.charAt(after) == '\r' && after + 1 < text.length() && text.charAt(after + 1) == '\n');
            if (lineStart && lineEnd) {
                return idx;
            }
            from = idx + 3;
        }
        return -1;
    }

    private static Map<String, String> parseSimpleYaml(String yaml) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) {
            return meta;
        }
        for (String line : yaml.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!key.isEmpty()) {
                meta.put(key, value);
            }
        }
        return meta;
    }

    static MarkdownStyle buildMarkdownStyle(String accent) {
        return buildMarkdownStyle(accent, false);
    }

    static MarkdownStyle buildMarkdownStyle(String accent, boolean showHeaderNumber) {
        String accentHex = normalizeHex(accent);
        MarkdownStyle style = MarkdownStyle.newStyle();
        style.setShowHeaderNumber(showHeaderNumber);

        Style headerText = Style.builder()
                .buildColor("FFFFFF")
                .buildBold()
                .build();
        ParagraphStyle headerParagraph = ParagraphStyle.builder()
                .withDefaultTextStyle(headerText)
                .build();
        CellStyle headerCell = new CellStyle();
        headerCell.setBackgroundColor(accentHex);
        headerCell.setDefaultParagraphStyle(headerParagraph);
        RowStyle tableHeader = new RowStyle();
        tableHeader.setDefaultCellStyle(headerCell);
        style.setTableHeaderStyle(tableHeader);

        BorderStyle border = BorderStyle.builder()
                .withColor("BFBFBF")
                .withSize(4)
                .withType(XWPFBorderType.SINGLE)
                .build();
        style.setTableBorderStyle(border);

        Style quoteText = Style.builder()
                .buildColor("555555")
                .buildItalic()
                .build();
        ParagraphStyle quote = ParagraphStyle.builder()
                .withDefaultTextStyle(quoteText)
                .withBackgroundColor("F5F5F5")
                .withLeftBorder(BorderStyle.builder()
                        .withColor(accentHex)
                        .withSize(24)
                        .withType(XWPFBorderType.SINGLE)
                        .build())
                .build();
        style.setQuoteStyle(quote);

        Style inlineCode = Style.builder()
                .buildColor("C7254E")
                .buildFontFamily("Consolas")
                .buildFontSize(10)
                .build();
        style.setInlineCodeStyle(inlineCode);

        return style;
    }

    private static String normalizeHex(String accent) {
        String hex = accent == null ? DEFAULT_ACCENT : accent.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (GenerateSpreadsheetTool.parseRgb("#" + hex) == null) {
            return DEFAULT_ACCENT.substring(1);
        }
        return hex.toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    /** Minimal poi-tl template: a single paragraph containing the {@code {{md}}} tag. */
    private static byte[] buildMarkdownTemplate() {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("{{md}}");
            doc.write(out);
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to build DOCX markdown template", failure);
        }
    }

    record FrontMatter(String body, Map<String, String> meta) {
    }

    record DocTheme(
            String accent,
            String title,
            String author,
            String template,
            boolean header,
            boolean footer,
            boolean pageNumbers
    ) {
        static DocTheme from(JsonNode themeNode, Map<String, String> frontMatter) {
            Map<String, String> fm = frontMatter == null ? Map.of() : frontMatter;

            String template = firstNonBlank(
                    text(themeNode, "template"),
                    fm.getOrDefault("template", "report")).trim().toLowerCase(Locale.ROOT);
            if (!List.of("report", "blank", "letter").contains(template)) {
                template = "report";
            }

            boolean defaultHeader = "report".equals(template);
            boolean defaultFooter = "report".equals(template) || "letter".equals(template);
            boolean defaultPageNumbers = "report".equals(template);

            // letter: footer only if page numbers asked; default no running chrome
            if ("letter".equals(template)) {
                defaultHeader = false;
                defaultFooter = false;
                defaultPageNumbers = false;
            }
            if ("blank".equals(template)) {
                defaultHeader = false;
                defaultFooter = false;
                defaultPageNumbers = false;
            }

            boolean header = boolOr(themeNode, "header", defaultHeader);
            boolean footer = boolOr(themeNode, "footer", defaultFooter);
            boolean pageNumbers = boolOr(themeNode, "page_numbers", defaultPageNumbers);
            if (pageNumbers && !footer) {
                footer = true;
            }

            String accent = firstNonBlank(text(themeNode, "accent"), fm.get("accent"));
            if (GenerateSpreadsheetTool.parseRgb(accent) == null) {
                accent = DEFAULT_ACCENT;
            }
            String title = firstNonBlank(text(themeNode, "title"), fm.get("title"));
            String author = firstNonBlank(text(themeNode, "author"), fm.get("author"));
            return new DocTheme(accent, blankToNull(title), blankToNull(author),
                    template, header, footer, pageNumbers);
        }

        private static String text(JsonNode node, String field) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.path(field).asText("").trim();
        }

        private static boolean boolOr(JsonNode node, String field, boolean fallback) {
            if (node == null || node.isMissingNode() || !node.has(field) || node.path(field).isNull()) {
                return fallback;
            }
            return node.path(field).asBoolean(fallback);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}

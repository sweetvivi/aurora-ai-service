package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateSpreadsheetToolTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");

    @Mock
    private FileService fileService;

    private GenerateSpreadsheetTool tool;

    @BeforeEach
    void setUp() {
        tool = new GenerateSpreadsheetTool(fileService, new ObjectMapper());
    }

    @Test
    void buildsXlsxAndReturnsFileMeta() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                22L, "预算.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx", FileAssetType.GENERATED, 99L, "abc", "UPLOADED", null, null,
                "/api/v1/files/22/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("预算.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "预算",
                  "sheets": [{
                    "name": "P&L",
                    "table": {
                      "columns": [
                        {"key":"line","header":"项目"},
                        {"key":"q1","header":"Q1","format":"currency"}
                      ],
                      "rows": [
                        {"line":"收入","q1":120000}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) outcome.meta().get("files");
        assertEquals(22L, files.get(0).get("fileId"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("预算.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("P&L", workbook.getSheetAt(0).getSheetName());
            assertEquals("项目", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("收入", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals(120000d, workbook.getSheetAt(0).getRow(1).getCell(1).getNumericCellValue());

            Cell header = workbook.getSheetAt(0).getRow(0).getCell(0);
            XSSFCellStyle headerStyle = (XSSFCellStyle) header.getCellStyle();
            assertEquals(FillPatternType.SOLID_FOREGROUND, headerStyle.getFillPattern());
            assertRgb(GenerateSpreadsheetTool.DEFAULT_ACCENT, headerStyle.getFillForegroundXSSFColor());
            XSSFFont headerFont = headerStyle.getFont();
            assertTrue(headerFont.getBold());
            assertRgb(GenerateSpreadsheetTool.DEFAULT_HEADER_COLOR, headerFont.getXSSFColor());
        }
    }

    @Test
    void appliesThemeColumnAndCellStyles() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                23L, "styled.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx", FileAssetType.GENERATED, 50L, "abc", "UPLOADED", null, null,
                "/api/v1/files/23/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("styled.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "styled",
                  "theme": { "accent": "#112233", "font": "Arial" },
                  "sheets": [{
                    "name": "Status",
                    "table": {
                      "columns": [
                        {"key":"name","header":"名称","headerBg":"#AABBCC","headerColor":"#000000"},
                        {"key":"status","header":"状态","bgColor":"#EEFFEE","fontColor":"#006600"}
                      ],
                      "rows": [
                        {"name":"A","status":"正常"},
                        {"name":"B","status":{"v":"逾期","bgColor":"#FFCDD2","fontColor":"#B71C1C","bold":true}}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("styled.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertRgb("#112233", sheet.getTabColor());

            XSSFCellStyle header0 = (XSSFCellStyle) sheet.getRow(0).getCell(0).getCellStyle();
            assertRgb("#AABBCC", header0.getFillForegroundXSSFColor());
            assertRgb("#000000", header0.getFont().getXSSFColor());
            assertEquals("Arial", header0.getFont().getFontName());

            XSSFCellStyle normal = (XSSFCellStyle) sheet.getRow(1).getCell(1).getCellStyle();
            assertRgb("#EEFFEE", normal.getFillForegroundXSSFColor());
            assertRgb("#006600", normal.getFont().getXSSFColor());
            assertEquals("正常", sheet.getRow(1).getCell(1).getStringCellValue());

            XSSFCellStyle overdue = (XSSFCellStyle) sheet.getRow(2).getCell(1).getCellStyle();
            assertEquals("逾期", sheet.getRow(2).getCell(1).getStringCellValue());
            assertRgb("#FFCDD2", overdue.getFillForegroundXSSFColor());
            assertRgb("#B71C1C", overdue.getFont().getXSSFColor());
            assertTrue(overdue.getFont().getBold());
        }
    }

    @Test
    void oralCase_redHeaderTextAndPinkNameFont_noExtraBackground() throws Exception {
        // 口语：「首行粗体红字、名字粉色」→ 字色入参，无多余底色；并回传样式摘要。
        UploadedFileResponse stored = storedXlsx(24L, "工资表.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("工资表.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "工资表",
                  "sheets": [{
                    "name": "工资表",
                    "table": {
                      "columns": [
                        {"key":"name","header":"姓名","headerColor":"#FF0000","headerBold":true,"fontColor":"#FF69B4"},
                        {"key":"pay","header":"基本工资","headerColor":"#FF0000","headerBold":true,"format":"currency"}
                      ],
                      "rows": [
                        {"name":"张三","pay":8000}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("已应用样式摘要"));
        assertTrue(outcome.content().contains("无底色"));
        assertTrue(outcome.content().contains("#FF0000"));
        assertTrue(outcome.content().contains("#FF69B4"));
        assertTrue(outcome.content().contains("列「姓名」数据区"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("工资表.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);

            XSSFCellStyle header = (XSSFCellStyle) sheet.getRow(0).getCell(0).getCellStyle();
            assertEquals(FillPatternType.NO_FILL, header.getFillPattern());
            assertTrue(header.getFont().getBold());
            assertRgb("#FF0000", header.getFont().getXSSFColor());

            XSSFCellStyle name = (XSSFCellStyle) sheet.getRow(1).getCell(0).getCellStyle();
            assertEquals(FillPatternType.NO_FILL, name.getFillPattern());
            assertRgb("#FF69B4", name.getFont().getXSSFColor());
            assertEquals("张三", sheet.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void oralCase_explicitRedHeaderBackground_keepsFill() throws Exception {
        // 口语：「表头红底白字」→ headerBg，不是把红字误写成无底。
        UploadedFileResponse stored = storedXlsx(25L, "表头红底.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("表头红底.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "表头红底",
                  "sheets": [{
                    "name": "明细",
                    "table": {
                      "columns": [
                        {"key":"item","header":"项目","headerBg":"#C00000","headerColor":"#FFFFFF","headerBold":true}
                      ],
                      "rows": [{"item":"差旅"}]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("底色#C00000"));
        assertTrue(outcome.content().contains("字色#FFFFFF"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("表头红底.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFCellStyle header = (XSSFCellStyle) workbook.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals(FillPatternType.SOLID_FOREGROUND, header.getFillPattern());
            assertRgb("#C00000", header.getFillForegroundXSSFColor());
            assertRgb("#FFFFFF", header.getFont().getXSSFColor());
        }
    }

    @Test
    void oralCase_pinkNameColumnBackground_notFontColor() throws Exception {
        // 口语：「名字粉底」→ 姓名列 bgColor，不是 fontColor。
        UploadedFileResponse stored = storedXlsx(26L, "粉底.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("粉底.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "粉底",
                  "sheets": [{
                    "name": "名单",
                    "table": {
                      "columns": [
                        {"key":"name","header":"姓名","bgColor":"#FFC0CB"},
                        {"key":"dept","header":"部门"}
                      ],
                      "rows": [{"name":"李四","dept":"研发"}]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("列「姓名」数据区底色#FFC0CB"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("粉底.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFCellStyle name = (XSSFCellStyle) workbook.getSheetAt(0).getRow(1).getCell(0).getCellStyle();
            assertEquals(FillPatternType.SOLID_FOREGROUND, name.getFillPattern());
            assertRgb("#FFC0CB", name.getFillForegroundXSSFColor());
        }
    }

    @Test
    void oralCase_headerBgNone_clearsAccentFill() throws Exception {
        // 口语：「只要字色、不要底」也可显式 headerBg=none。
        UploadedFileResponse stored = storedXlsx(27L, "无底.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("无底.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "无底",
                  "sheets": [{
                    "name": "Sheet1",
                    "table": {
                      "columns": [
                        {"key":"a","header":"A","headerBg":"none","headerColor":"#0000FF"}
                      ],
                      "rows": [{"a":"x"}]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("无底色"));
        assertTrue(outcome.content().contains("#0000FF"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("无底.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFCellStyle header = (XSSFCellStyle) workbook.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals(FillPatternType.NO_FILL, header.getFillPattern());
            assertRgb("#0000FF", header.getFont().getXSSFColor());
        }
    }

    @Test
    void rejectsMissingSheets() {
        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, "{\"title\":\"x\"}"),
                ToolContext.of(USER));
        assertTrue(!outcome.success());
        assertTrue(outcome.content().contains("sheets"));
    }

    @Test
    void p1_freezeExcelTableAndColumnFormula() throws Exception {
        UploadedFileResponse stored = storedXlsx(28L, "预算明细.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("预算明细.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "预算明细",
                  "sheets": [{
                    "name": "预算",
                    "freeze": "A2",
                    "table": {
                      "excel_table": true,
                      "zebra": true,
                      "name": "BudgetTable",
                      "columns": [
                        {"key":"item","header":"项目"},
                        {"key":"qty","header":"数量","format":"integer"},
                        {"key":"price","header":"单价","format":"currency"},
                        {"key":"amount","header":"金额","format":"currency","formula":"={qty}*{price}"}
                      ],
                      "rows": [
                        {"item":"显示器","qty":2,"price":1200},
                        {"item":"键盘","qty":5,"price":80}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("冻结A2"));
        assertTrue(outcome.content().contains("Excel Table"));
        assertTrue(outcome.content().contains("公式列「金额」"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("预算明细.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
            assertTrue(sheet.getPaneInformation().isFreezePane());

            assertFalse(sheet.getTables().isEmpty());
            XSSFTable table = sheet.getTables().get(0);
            assertEquals("BudgetTable", table.getDisplayName());
            assertTrue(table.getCTTable().getTableStyleInfo().getShowRowStripes());
            assertNotNull(table.getCTTable().getAutoFilter());

            Cell amount = sheet.getRow(1).getCell(3);
            assertEquals(CellType.FORMULA, amount.getCellType());
            assertEquals("B2*C2", amount.getCellFormula());

            Cell amount2 = sheet.getRow(2).getCell(3);
            assertEquals("B3*C3", amount2.getCellFormula());
        }
    }

    @Test
    void p1_cellFormulaAndAutoFilterWithoutTable() throws Exception {
        UploadedFileResponse stored = storedXlsx(29L, "合计.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("合计.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "合计",
                  "sheets": [{
                    "name": "Sheet1",
                    "table": {
                      "auto_filter": true,
                      "columns": [
                        {"key":"a","header":"A","format":"number"},
                        {"key":"b","header":"B"}
                      ],
                      "rows": [
                        {"a":10,"b":"x"},
                        {"a":20,"b":"y"},
                        {"a":"=SUM(A2:A3)","b":"合计"}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("自动筛选"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("合计.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertTrue(sheet.getTables().isEmpty());
            assertNotNull(sheet.getCTWorksheet().getAutoFilter());
            Cell sum = sheet.getRow(3).getCell(0);
            assertEquals(CellType.FORMULA, sum.getCellType());
            assertEquals("SUM(A2:A3)", sum.getCellFormula());
        }
    }

    @Test
    void p1_totalsRowBelowExcelTable_withSumAndAvg() throws Exception {
        UploadedFileResponse stored = storedXlsx(30L, "工资合计.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("工资合计.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "工资合计",
                  "sheets": [{
                    "name": "工资",
                    "table": {
                      "excel_table": true,
                      "totals_row": true,
                      "totals": {"label": "合计", "bonus": "avg"},
                      "columns": [
                        {"key":"name","header":"姓名"},
                        {"key":"pay","header":"基本工资","format":"currency"},
                        {"key":"bonus","header":"奖金","format":"currency"}
                      ],
                      "rows": [
                        {"name":"张三","pay":8000,"bonus":1000},
                        {"name":"李四","pay":9000,"bonus":2000}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("合计行「合计」"));
        assertTrue(outcome.content().contains("Excel Table"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("工资合计.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);

            // Table covers header + data only (rows 0..2), not the totals row.
            XSSFTable table = sheet.getTables().get(0);
            assertEquals(2, table.getEndCellReference().getRow());

            Row totals = sheet.getRow(3);
            assertEquals("合计", totals.getCell(0).getStringCellValue());
            assertEquals(CellType.FORMULA, totals.getCell(1).getCellType());
            assertEquals("SUM(B2:B3)", totals.getCell(1).getCellFormula());
            assertEquals(CellType.FORMULA, totals.getCell(2).getCellType());
            assertEquals("AVERAGE(C2:C3)", totals.getCell(2).getCellFormula());
            assertTrue(((XSSFCellStyle) totals.getCell(0).getCellStyle()).getFont().getBold());
        }
    }

    @Test
    void p1_columnWidthAlignAndWrap() throws Exception {
        UploadedFileResponse stored = storedXlsx(31L, "列宽对齐.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("列宽对齐.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "列宽对齐",
                  "sheets": [{
                    "name": "明细",
                    "table": {
                      "columns": [
                        {"key":"note","header":"备注","width":28,"align":"left","wrap":true},
                        {"key":"amt","header":"金额","format":"currency","width":12,"align":"right"}
                      ],
                      "rows": [
                        {"note":"这是一段需要换行的很长很长很长很长的备注文字","amt":1234.5}
                      ]
                    }
                  }]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("列宽对齐.xlsx"), eq(FileAssetType.GENERATED), any());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals(28 * 256, sheet.getColumnWidth(0));
            assertEquals(12 * 256, sheet.getColumnWidth(1));

            XSSFCellStyle note = (XSSFCellStyle) sheet.getRow(1).getCell(0).getCellStyle();
            assertEquals(HorizontalAlignment.LEFT, note.getAlignment());
            assertTrue(note.getWrapText());

            XSSFCellStyle amt = (XSSFCellStyle) sheet.getRow(1).getCell(1).getCellStyle();
            assertEquals(HorizontalAlignment.RIGHT, amt.getAlignment());
        }
    }

    @Test
    void expandColumnFormula_supportsBracketAndBraceKeys() {
        Map<String, Integer> keys = new LinkedHashMap<>();
        keys.put("q1", 1);
        keys.put("q4", 4);
        keys.put("qty", 0);
        keys.put("price", 2);

        assertEquals("=SUM(B5:E5)",
                GenerateSpreadsheetTool.expandColumnFormula("=SUM([{q1}]:[{q4}])", 5, keys));
        assertEquals("=A3*C3",
                GenerateSpreadsheetTool.expandColumnFormula("{qty}*{price}", 3, keys));
        assertEquals("A", CellReference.convertNumToColString(0));
    }

    @Test
    void usesFirstSheetAsFilenameAndKeepsNamedBusinessSheets() throws Exception {
        UploadedFileResponse stored = storedXlsx(32L, "工资明细.xlsx");
        when(fileService.storeGenerated(any(), any(), eq("xlsx"), any(), eq("工资明细.xlsx"),
                eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "sheets": [
                    {
                      "name": "工资明细",
                      "table": {
                        "columns": [{"key":"name","header":"姓名"}],
                        "rows": [{"name":"张三"}]
                      }
                    },
                    {
                      "name": "部门汇总",
                      "table": {
                        "columns": [{"key":"department","header":"部门"}],
                        "rows": [{"department":"研发部"}]
                      }
                    }
                  ]
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateSpreadsheetTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("xlsx"), any(),
                eq("工资明细.xlsx"), eq(FileAssetType.GENERATED), any());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("工资明细", workbook.getSheetName(0));
            assertEquals("部门汇总", workbook.getSheetName(1));
        }
    }

    private static UploadedFileResponse storedXlsx(long id, String name) {
        return new UploadedFileResponse(
                id, name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx", FileAssetType.GENERATED, 80L, "abc", "UPLOADED", null, null,
                "/api/v1/files/" + id + "/content", LocalDateTime.now());
    }

    private static void assertRgb(String expectedHex, XSSFColor color) {
        assertNotNull(color);
        byte[] expected = GenerateSpreadsheetTool.parseRgb(expectedHex);
        assertNotNull(expected);
        assertArrayEquals(expected, color.getRGB());
    }
}

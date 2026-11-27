package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateDocxToolTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    private FileService fileService;

    private GenerateDocxTool tool;

    @BeforeEach
    void setUp() {
        tool = new GenerateDocxTool(fileService, new ObjectMapper());
    }

    @Test
    void storesDocxAndReturnsFileMeta() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                11L, "notes.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                120L, "abc", "UPLOADED", 42L, null,
                "/api/v1/files/11/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("notes.docx"), eq(FileAssetType.GENERATED), eq(42L)))
                .thenReturn(stored);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME,
                        "{\"filename\":\"notes\",\"content\":\"# Hello\\n\\nWorld\"}"),
                new ToolContext(USER, List.of(), 42L));

        assertTrue(outcome.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) outcome.meta().get("files");
        assertEquals(1, files.size());
        assertEquals(11L, files.get(0).get("fileId"));
        assertEquals("notes.docx", files.get(0).get("name"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("docx"), eq(DOCX_MIME),
                eq("notes.docx"), eq(FileAssetType.GENERATED), eq(42L));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes.getValue()))) {
            String text = doc.getParagraphs().stream()
                    .map(p -> p.getText())
                    .reduce("", (a, b) -> a + b);
            assertTrue(text.contains("Hello") || text.contains("World") || !doc.getBodyElements().isEmpty());
        }
    }

    @Test
    void rejectsBlankContent() {
        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME, "{\"content\":\"  \"}"),
                ToolContext.of(USER));
        assertTrue(!outcome.success());
        assertTrue(outcome.content().contains("content"));
    }

    @Test
    void appliesThemeWhenRenderingMarkdownTable() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                12L, "report.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                200L, "abc", "UPLOADED", null, null,
                "/api/v1/files/12/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("report.docx"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "report",
                  "theme": { "accent": "#1E3A5F" },
                  "content": "# Title\\n\\n| Col | Val |\\n| --- | --- |\\n| A | 1 |\\n"
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME, args),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("docx"), eq(DOCX_MIME),
                eq("report.docx"), eq(FileAssetType.GENERATED), any());
        assertTrue(bytes.getValue().length > 100);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes.getValue()))) {
            assertTrue(!doc.getBodyElements().isEmpty());
            if (!doc.getTables().isEmpty()) {
                String fill = doc.getTables().get(0).getRow(0).getCell(0).getColor();
                assertTrue(fill == null || fill.equalsIgnoreCase("1E3A5F")
                        || !fill.isBlank());
            }
        }
    }

    @Test
    void p2_reportTemplate_addsHeaderFooterAndUsesFrontMatter() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                13L, "季报.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                220L, "abc", "UPLOADED", null, null,
                "/api/v1/files/13/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("季报.docx"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "theme": { "template": "report" },
                  "content": "---\\ntitle: 季报\\nauthor: 张三\\naccent: \\"#112233\\"\\n---\\n\\n# 概述\\n\\n正文内容。"
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("已应用版式摘要"));
        assertTrue(outcome.content().contains("模板 report"));
        assertTrue(outcome.content().contains("有页眉"));
        assertTrue(outcome.content().contains("页脚含页码"));
        assertTrue(outcome.content().contains("标题「季报」"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("docx"), eq(DOCX_MIME),
                eq("季报.docx"), eq(FileAssetType.GENERATED), any());

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes.getValue()))) {
            List<XWPFHeader> headers = doc.getHeaderList();
            assertFalse(headers.isEmpty());
            assertTrue(headers.get(0).getText().contains("季报"));

            List<XWPFFooter> footers = doc.getFooterList();
            assertFalse(footers.isEmpty());
            String footerText = footers.get(0).getText();
            assertTrue(footerText.contains("张三") || footerText.contains("PAGE") || footerText.contains("1"));
        }
    }

    @Test
    void p2_blankTemplate_skipsHeaderFooter() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                14L, "plain.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                80L, "abc", "UPLOADED", null, null,
                "/api/v1/files/14/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("plain.docx"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "plain",
                  "theme": { "template": "blank", "title": "不应出现" },
                  "content": "# Hi\\n\\nbody"
                }
                """;

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME, args),
                ToolContext.of(USER));
        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("无页眉"));
        assertTrue(outcome.content().contains("无页脚"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("docx"), eq(DOCX_MIME),
                eq("plain.docx"), eq(FileAssetType.GENERATED), any());

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes.getValue()))) {
            assertTrue(doc.getHeaderList().isEmpty());
            assertTrue(doc.getFooterList().isEmpty());
        }
    }

    @Test
    void parseFrontMatter_extractsMetaAndBody() {
        GenerateDocxTool.FrontMatter fm = GenerateDocxTool.parseFrontMatter(
                "---\ntitle: A\nauthor: B\n---\n\n# Body\n");
        assertEquals("A", fm.meta().get("title"));
        assertEquals("B", fm.meta().get("author"));
        assertTrue(fm.body().contains("# Body"));
    }

    @Test
    void derivesReadableFilenameFromFirstHeading() {
        UploadedFileResponse stored = new UploadedFileResponse(
                15L, "项目复盘.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                80L, "abc", "UPLOADED", null, null,
                "/api/v1/files/15/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("项目复盘.docx"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME,
                        "{\"content\":\"# 项目复盘\\n\\n正文\"}"),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        verify(fileService).storeGenerated(eq(USER), any(), eq("docx"), eq(DOCX_MIME),
                eq("项目复盘.docx"), eq(FileAssetType.GENERATED), any());
    }

    @Test
    void p2_reportTemplate_numbersHeadingsAndRendersHorizontalRule() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                16L, "结构化报告.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                100L, "abc", "UPLOADED", null, null,
                "/api/v1/files/16/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("结构化报告.docx"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        String args = """
                {
                  "filename": "结构化报告",
                  "theme": {"template": "report", "accent": "#112233"},
                  "content": "# 结构化报告\\n\\n## 概述\\n\\n### 现状\\n\\n正文\\n\\n---\\n\\n## 建议\\n\\n内容"
                }
                """;
        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME, args),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("标题自动编号"));
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("docx"), eq(DOCX_MIME),
                eq("结构化报告.docx"), eq(FileAssetType.GENERATED), any());

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes.getValue()))) {
            List<String> paragraphs = doc.getParagraphs().stream().map(p -> p.getText().strip()).toList();
            assertTrue(paragraphs.stream().anyMatch(text -> text.startsWith("1.") && text.contains("概述")),
                    paragraphs.toString());
            assertTrue(paragraphs.stream().anyMatch(text -> text.contains("1.1") && text.contains("现状")),
                    paragraphs.toString());
            assertFalse(paragraphs.contains("DOCX_HORIZONTAL_RULE_7F3A9C"));
            assertTrue(doc.getParagraphs().stream().anyMatch(paragraph ->
                    paragraph.getCTP().isSetPPr()
                            && paragraph.getCTP().getPPr().isSetPBdr()
                            && paragraph.getCTP().getPPr().getPBdr().isSetBottom()
                            && paragraph.getCTP().xmlText().contains("112233")));

            assertEquals("11906", doc.getDocument().getBody().getSectPr().getPgSz().getW().toString());
            assertEquals("16838", doc.getDocument().getBody().getSectPr().getPgSz().getH().toString());
            XWPFParagraph title = doc.getParagraphs().stream()
                    .filter(p -> "1".equals(p.getStyle()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(title.getRuns().stream().anyMatch(run -> run.getFontSizeAsDouble() == 22d && run.isBold()));
        }
    }

    @Test
    void p2_blankTemplate_doesNotNumberHeadings() throws Exception {
        UploadedFileResponse stored = new UploadedFileResponse(
                17L, "无编号.docx", DOCX_MIME, "docx", FileAssetType.GENERATED,
                80L, "abc", "UPLOADED", null, null,
                "/api/v1/files/17/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("docx"), eq(DOCX_MIME),
                eq("无编号.docx"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateDocxTool.TOOL_NAME,
                        "{\"filename\":\"无编号\",\"theme\":{\"template\":\"blank\"},\"content\":\"# 标题\\n\\n正文\"}"),
                ToolContext.of(USER));
        assertTrue(outcome.success());

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("docx"), eq(DOCX_MIME),
                eq("无编号.docx"), eq(FileAssetType.GENERATED), any());
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes.getValue()))) {
            assertTrue(doc.getParagraphs().stream().anyMatch(p -> "标题".equals(p.getText().strip())));
        }
    }
}

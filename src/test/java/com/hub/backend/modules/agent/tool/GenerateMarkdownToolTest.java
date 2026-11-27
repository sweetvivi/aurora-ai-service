package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateMarkdownToolTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");

    @Mock
    private FileService fileService;

    private GenerateMarkdownTool tool;

    @BeforeEach
    void setUp() {
        tool = new GenerateMarkdownTool(fileService, new ObjectMapper());
    }

    @Test
    void storesMarkdownAndReturnsFileMeta() {
        UploadedFileResponse stored = new UploadedFileResponse(
                11L, "notes.md", "text/markdown", "md", FileAssetType.GENERATED,
                12L, "abc", "UPLOADED", 42L, null,
                "/api/v1/files/11/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("md"), eq("text/markdown"),
                eq("notes.md"), eq(FileAssetType.GENERATED), eq(42L)))
                .thenReturn(stored);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateMarkdownTool.TOOL_NAME,
                        "{\"filename\":\"notes\",\"content\":\"# Hello\"}"),
                new ToolContext(USER, List.of(), 42L));

        assertTrue(outcome.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) outcome.meta().get("files");
        assertEquals(1, files.size());
        assertEquals(11L, files.get(0).get("fileId"));
        assertEquals("notes.md", files.get(0).get("name"));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).storeGenerated(eq(USER), bytes.capture(), eq("md"), eq("text/markdown"),
                eq("notes.md"), eq(FileAssetType.GENERATED), eq(42L));
        assertEquals("# Hello", new String(bytes.getValue()));
    }

    @Test
    void rejectsBlankContent() {
        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateMarkdownTool.TOOL_NAME, "{\"content\":\"  \"}"),
                ToolContext.of(USER));
        assertTrue(!outcome.success());
        assertTrue(outcome.content().contains("content"));
    }

    @Test
    void derivesReadableFilenameFromMarkdownHeading() {
        UploadedFileResponse stored = new UploadedFileResponse(
                12L, "季度经营报告.md", "text/markdown", "md", FileAssetType.GENERATED,
                42L, "abc", "UPLOADED", null, null,
                "/api/v1/files/12/content", LocalDateTime.now());
        when(fileService.storeGenerated(any(), any(), eq("md"), eq("text/markdown"),
                eq("季度经营报告.md"), eq(FileAssetType.GENERATED), any()))
                .thenReturn(stored);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", GenerateMarkdownTool.TOOL_NAME,
                        "{\"content\":\"# 季度经营报告\\n\\n正文\"}"),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        verify(fileService).storeGenerated(eq(USER), any(), eq("md"), eq("text/markdown"),
                eq("季度经营报告.md"), eq(FileAssetType.GENERATED), any());
    }

    @Test
    void sanitizesWindowsInvalidFilenameCharacters() {
        assertEquals("2026_预算_终版_.xlsx",
                GenerateMarkdownTool.sanitizeFilename("2026:预算/终版?", "workbook.xlsx", "xlsx"));
    }
}

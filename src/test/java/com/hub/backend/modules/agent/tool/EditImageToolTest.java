package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.smartedit.SmartEditImageService;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditImageToolTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");

    @Mock
    private SmartEditImageService imageService;

    private EditImageTool tool;

    @BeforeEach
    void setUp() {
        tool = new EditImageTool(imageService, new ObjectMapper());
    }

    @Test
    void failsWhenNoFocusImage() {
        ToolContext context = new ToolContext(USER, List.of(), 42L, null, 1, List.of(),
                FileSource.IMAGE_GENERATION.value(), null);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("1", EditImageTool.TOOL_NAME, "{\"instruction\":\"换背景\"}"),
                context);

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("没有可修改的图片"));
    }

    @Test
    void editsFocusImageAndReturnsFileMeta() {
        when(imageService.compose(any(), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(new ImageUrlResponse("/api/v1/files/88/content", FileAssetType.EDIT_RESULT));

        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        ToolContext context = new ToolContext(
                USER,
                List.of(),
                42L,
                "gpt-image-2",
                1,
                List.of(new LlmImageAttachment("image/png", png)),
                FileSource.IMAGE_GENERATION.value(),
                null);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("1", EditImageTool.TOOL_NAME, "{\"instruction\":\"换成白天\"}"),
                context);

        assertTrue(outcome.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) outcome.meta().get("images");
        assertEquals(1, images.size());
        assertEquals(88L, ((Number) images.get(0).get("fileId")).longValue());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imageService).compose(
                eq(USER),
                anyList(),
                prompt.capture(),
                eq("gpt-image-2"),
                isNull(),
                eq(FileSource.IMAGE_GENERATION),
                eq(42L));
        assertEquals("换成白天", prompt.getValue());
    }

    @Test
    void acceptsPromptAliasForInstruction() {
        when(imageService.compose(any(), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(new ImageUrlResponse("/api/v1/files/9/content", FileAssetType.EDIT_RESULT));

        ToolContext context = new ToolContext(
                USER,
                List.of(),
                null,
                null,
                null,
                List.of(new LlmImageAttachment("image/png", new byte[] {1, 2, 3})),
                FileSource.IMAGE_GENERATION.value(),
                null);

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("1", EditImageTool.TOOL_NAME, "{\"prompt\":\"加帽子\"}"),
                context);

        assertTrue(outcome.success());
        verify(imageService).compose(
                eq(USER), anyList(), eq("加帽子"), isNull(), isNull(),
                eq(FileSource.IMAGE_GENERATION), isNull());
    }

    @Test
    void parsesFileIdFromUrl() {
        assertEquals(123L, EditImageTool.fileIdFromUrl("/api/v1/files/123/content"));
        assertEquals(null, EditImageTool.fileIdFromUrl("https://cdn.example/a.png"));
    }
}

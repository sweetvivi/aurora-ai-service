package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.image.ImageGenerationService;
import com.hub.backend.modules.image.dto.GeneratedImageResponse;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationToolTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");

    @Mock
    private ImageGenerationService imageGenerationService;

    private ImageGenerationTool tool;

    @BeforeEach
    void setUp() {
        tool = new ImageGenerationTool(imageGenerationService, new ObjectMapper());
    }

    @Test
    void prefersToolContextSizeOverModelArgument() {
        when(imageGenerationService.generate(eq(USER), any()))
                .thenReturn(sampleResponse(1L));

        ToolContext context = new ToolContext(
                USER,
                List.of(),
                9L,
                "gpt-image-2",
                2,
                List.of(),
                FileSource.IMAGE_GENERATION.value(),
                "1792x1024");

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("1", ImageGenerationTool.TOOL_NAME,
                        "{\"prompt\":\"赛博朋克猫\",\"size\":\"1024x1024\"}"),
                context);

        assertTrue(outcome.success());
        ArgumentCaptor<ImageGenerationRequest> captor = ArgumentCaptor.forClass(ImageGenerationRequest.class);
        verify(imageGenerationService).generate(eq(USER), captor.capture());
        assertEquals("赛博朋克猫", captor.getValue().prompt());
        assertEquals("1792x1024", captor.getValue().size());
        assertEquals(2, captor.getValue().countOrDefault());
        assertEquals("gpt-image-2", captor.getValue().model());
    }

    @Test
    void fallsBackToModelSizeWhenContextMissing() {
        when(imageGenerationService.generate(eq(USER), any()))
                .thenReturn(sampleResponse(2L));

        ToolContext context = new ToolContext(
                USER, List.of(), null, null, 1, List.of(), FileSource.AGENT.value(), null);

        tool.execute(
                new LlmToolCall("1", ImageGenerationTool.TOOL_NAME,
                        "{\"prompt\":\"猫\",\"size\":\"1024x1536\"}"),
                context);

        ArgumentCaptor<ImageGenerationRequest> captor = ArgumentCaptor.forClass(ImageGenerationRequest.class);
        verify(imageGenerationService).generate(eq(USER), captor.capture());
        assertEquals("1024x1536", captor.getValue().size());
    }

    @Test
    void resolveSizeIgnoresUnknownValues() {
        assertEquals("1024x1024", ImageGenerationTool.resolveSize(null, "nonsense"));
        assertEquals("auto", ImageGenerationTool.resolveSize("auto", "1024x1024"));
        assertEquals("1024x1792", ImageGenerationTool.resolveSize("1024x1792", null));
    }

    /**
     * 失败文案会进模型上下文（模型会转述成正文），也可能被前端当成提示。
     * 它只能说「没出图」，不能出现「上游」这类内部链路词 —— 用户不该知道我们背后还有中转。
     */
    @Test
    void failureMessageNeverMentionsUpstream() {
        when(imageGenerationService.generate(eq(USER), any()))
                .thenThrow(new LlmInvocationException(
                        "Image gateway returned HTTP 502: {\"error\":{\"message\":\"upstream unavailable\"}}",
                        502,
                        null));

        ToolContext context = new ToolContext(
                USER, List.of(), null, "gpt-image-2", 1, List.of(), FileSource.AGENT.value(), null);
        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("1", ImageGenerationTool.TOOL_NAME, "{\"prompt\":\"猫\"}"),
                context);

        assertTrue(!outcome.success());
        String text = outcome.content();
        assertTrue(text.contains("没有生成出图片"), text);
        assertTrue(!text.contains("上游"), text);
        assertTrue(!text.toLowerCase().contains("gateway"), text);
        assertTrue(!text.contains("HTTP"), text);
    }

    private static ImageGenerationResponse sampleResponse(long fileId) {
        return new ImageGenerationResponse(
                Instant.parse("2024-01-01T00:00:00Z"),
                "gpt-image-2",
                1,
                List.of(new GeneratedImageResponse(
                        fileId,
                        "/api/v1/files/" + fileId + "/content",
                        true,
                        FileAssetType.GENERATED,
                        "image/png",
                        12L,
                        null)));
    }
}

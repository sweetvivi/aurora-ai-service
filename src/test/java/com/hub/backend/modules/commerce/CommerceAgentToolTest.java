package com.hub.backend.modules.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.modules.agent.tool.AgentTool;
import com.hub.backend.modules.agent.tool.ToolContext;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import com.hub.backend.modules.smartedit.SmartEditImageService;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能体调度电商工具的回归。
 *
 * <p>这一层的价值不在「能不能出图」（图像接口自己测），而在三件事：</p>
 * <ol>
 *   <li>参考图必须真的传进图像接口 —— 换背景、试穿没有原图是无意义的；</li>
 *   <li>工具页那套「第几张图是什么」的说明要跟着走，否则会重演「出了图但没穿上衣服」；</li>
 *   <li>模型给的 {@code params} 是对象，工具页提交的是数组，两条形态都得吃。</li>
 * </ol>
 */
class CommerceAgentToolTest {

    private static final Long SITE_ID = 1L;
    private static final CurrentUser USER = new CurrentUser(7L, SITE_ID, "tester");

    private CommerceToolRepository tools;
    private SmartEditImageService imageService;
    private CommerceAgentTool tool;

    @BeforeEach
    void setUp() {
        tools = mock(CommerceToolRepository.class);
        imageService = mock(SmartEditImageService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        // 真实的提示词拼装逻辑：这里要验的就是它写出来的那段话
        CommerceComposeChatService prompts = new CommerceComposeChatService(
                null, null, null, null, null, null, objectMapper, null);
        tool = new CommerceAgentTool(tools, imageService, prompts, objectMapper);
    }

    @Test
    void withoutImagesTheToolRefusesInsteadOfHallucinating() {
        AgentTool.ToolOutcome outcome = tool.execute(
                call("{\"toolKey\":\"virtual-model\",\"prompt\":\"换成外模\"}"),
                new ToolContext(USER, List.of()));

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("参考图"), outcome.content());
        verify(imageService, never()).compose(any(), anyList(), anyString(), any(), any());
    }

    @Test
    void unknownToolKeyIsRejectedWithAHint() {
        when(tools.findFirstBySiteIdAndToolKeyAndStatus(eq(SITE_ID), eq("nope"), anyString()))
                .thenReturn(Optional.empty());
        when(tools.findByToolKey("nope")).thenReturn(List.of());

        AgentTool.ToolOutcome outcome = tool.execute(
                call("{\"toolKey\":\"nope\",\"prompt\":\"随便\"}"),
                contextWithOneImage());

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("没有找到工具"), outcome.content());
    }

    @Test
    @SuppressWarnings("unchecked")
    void referenceImagesAndTheSlotLegendReachTheImageEndpoint() {
        stubTool();
        when(imageService.compose(any(), anyList(), anyString(), any(), any()))
                .thenReturn(new ImageUrlResponse("/api/v1/files/99/content", null));

        AgentTool.ToolOutcome outcome = tool.execute(
                call("{\"toolKey\":\"virtual-model\",\"prompt\":\"让第二张图的模特穿上第一张图的衣服\"}"),
                contextWithOneImage());

        assertTrue(outcome.success(), outcome.content());
        assertEquals("/api/v1/files/99/content",
                ((List<Map<String, Object>>) outcome.meta().get("images")).get(0).get("url"));

        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imageService).compose(eq(USER), files.capture(), prompt.capture(), eq(null), eq(null));

        // 上传的字节原样进图像接口，文件名带合法扩展名（图像接口按扩展名校验）
        assertEquals(1, files.getValue().size());
        assertEquals("reference-1.png", files.getValue().get(0).getOriginalFilename());
        assertEquals("image/png", files.getValue().get(0).getContentType());
        // 基准提示词 + 「第 1 张是什么」都在这段话里
        assertTrue(prompt.getValue().contains("让第二张图的模特穿上第一张图的衣服"), prompt.getValue());
        assertTrue(prompt.getValue().contains("第 1 张：上传服装图"), prompt.getValue());
    }

    @Test
    void objectStyleParamsBecomeTheArrayShapeTheComposerExpects() {
        stubTool();
        when(imageService.compose(any(), anyList(), anyString(), any(), any()))
                .thenReturn(new ImageUrlResponse("/api/v1/files/100/content", null));

        AgentTool.ToolOutcome outcome = tool.execute(
                call("""
                        {"toolKey":"virtual-model","prompt":"换成模特图",
                         "params":{"模特类型":"女模","背景":"纯色"}}
                        """),
                contextWithOneImage());

        assertTrue(outcome.success(), outcome.content());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(imageService).compose(any(), anyList(), prompt.capture(), any(), any());
        assertTrue(prompt.getValue().contains("模特类型：女模"), prompt.getValue());
        assertTrue(prompt.getValue().contains("背景：纯色"), prompt.getValue());
    }

    @Test
    void catalogListsActiveToolsSoTheModelKnowsWhatItCanCall() {
        CommerceToolEntity row = toolRow();
        // 只列电商场景：绘画工具与它共用一张表，混进来会让每轮对话白背几十条用不上的清单
        when(tools.findBySceneAndStatusOrderBySortOrderAscIdAsc(
                CommerceToolEntity.SCENE_COMMERCE, CommerceToolEntity.STATUS_ACTIVE))
                .thenReturn(List.of(row));

        String description = tool.definition().description();

        assertTrue(description.contains("virtual-model"), description);
        assertTrue(description.contains("虚拟模特试穿"), description);
        assertTrue(description.contains("第一张图 / 第二张图"), description);
    }

    @Test
    void catalogFailureOnlyBlanksTheListAndNeverBreaksTheRegistry() {
        // 注册表在启动时就要拿 definition()，这里抛异常会把整个应用带崩
        when(tools.findBySceneAndStatusOrderBySortOrderAscIdAsc(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        String description = tool.definition().description();

        assertTrue(description.contains("当前没有启用的电商工具"), description);
    }

    /* ----------------------------- 夹具 ----------------------------- */

    private void stubTool() {
        when(tools.findFirstBySiteIdAndToolKeyAndStatus(
                eq(SITE_ID), eq("virtual-model"), eq(CommerceToolEntity.STATUS_ACTIVE)))
                .thenReturn(Optional.of(toolRow()));
    }

    private static CommerceToolEntity toolRow() {
        CommerceToolEntity tool = CommerceToolEntity.newTool();
        tool.setSiteId(SITE_ID);
        tool.setToolKey("virtual-model");
        tool.setCategory("模特替换");
        tool.setName("虚拟模特试穿");
        tool.setDescription("生成模特试穿和佩戴效果，保持商品版型与材质准确");
        tool.setPrompt("生成自然模特试穿效果，保持商品版型、颜色和材质准确。");
        tool.setFormSchema("""
                {"uploads":[
                  {"key":"clothing","label":"上传服装图"},
                  {"key":"model","label":"上传模特参考图"}
                ]}
                """);
        return tool;
    }

    private static ToolContext contextWithOneImage() {
        return new ToolContext(USER, List.of(
                new LlmImageAttachment("image/png", "fake-png".getBytes(StandardCharsets.UTF_8))));
    }

    private static LlmToolCall call(String arguments) {
        return new LlmToolCall("call-1", CommerceAgentTool.TOOL_NAME, arguments);
    }
}

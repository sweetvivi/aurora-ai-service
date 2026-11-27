package com.hub.backend.modules.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.smartedit.SmartEditImageService;
import com.hub.backend.modules.appconfig.ModuleChatModelService;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 试衣类工具「出了图但没穿上指定服装」的回归。
 *
 * <p>根因不是图像模型不行，而是对话模型根本不知道第几张图是什么：系统提示词里只有
 * 「生成自然模特试穿效果」这种笼统描述，润色出来的提示词也就不会区分服装与模特。
 * 图像模型拿到两张图与一句笼统指令，最省力的做法是重绘其中一张。</p>
 *
 * <p>这组断言守住「按上传槽顺序把图片角色讲清楚」这件事，顺序错了（比如模特图排在前面）
 * 同样会导致指代反了。</p>
 */
class CommerceComposeChatServiceTest {

    private final CommerceComposeChatService service = new CommerceComposeChatService(
            null, null, null, null, null, null, new ObjectMapper(), null);

    @Test
    void resolvesCommerceModuleChatCandidates() {
        ModelUseResolver resolver = mock(ModelUseResolver.class);
        ModuleChatModelService moduleModels = mock(ModuleChatModelService.class);
        CommerceComposeChatService subject = new CommerceComposeChatService(
                null, null, null, resolver, moduleModels, null, new ObjectMapper(), null);
        List<ModelUseResolver.Resolved> candidates = List.of(new ModelUseResolver.Resolved(
                1L, "commerce-chat", "upstream", LlmModelEntity.TYPE_CHAT, true,
                false, null, false, null, null));
        when(moduleModels.resolveAllForSite(1L, ModuleChatModelService.COMMERCE))
                .thenReturn(candidates);

        assertEquals(candidates, subject.chatModels(1L));
        verify(moduleModels).resolveAllForSite(1L, ModuleChatModelService.COMMERCE);
    }

    /**
     * 产出归属：绘画工具库的图要进绘画作品，电商工具保持原样。
     *
     * <p>这条曾经踩过 —— `SmartEditImageService.storeGenerated` 把 source 写死成
     * `SMART_EDIT`，于是从 `/painting` 点「智能抠图」生成的图会出现在「修图成果」里。</p>
     */
    @Test
    void paintingToolsStoreResultsUnderThePaintingSource() {
        CommerceToolEntity painting = CommerceToolEntity.newTool();
        painting.setScene(CommerceToolEntity.SCENE_PAINTING);

        CommerceToolEntity commerce = CommerceToolEntity.newTool();
        commerce.setScene(CommerceToolEntity.SCENE_COMMERCE);

        assertEquals(FileSource.PAINTING, CommerceComposeChatService.sourceOf(painting));
        assertEquals(FileSource.SMART_EDIT, CommerceComposeChatService.sourceOf(commerce));
    }

    @Test
    void legendsFollowTheUploadSlotOrder() {
        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[
                  {"key":"clothing","label":"上传服装图","hint":"服装平铺图或已有上身图"},
                  {"key":"model","label":"上传模特参考图","hint":"目标模特的脸部或全身参考"}
                ]}
                """);

        assertEquals(List.of(
                "上传服装图（服装平铺图或已有上身图）",
                "上传模特参考图（目标模特的脸部或全身参考）"), service.uploadLegends(tool));
    }

    @Test
    void hintIsOptional() {
        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[{"key":"product","label":"上传商品图"}]}
                """);

        assertEquals(List.of("上传商品图"), service.uploadLegends(tool));
    }

    @Test
    void blockTellsTheModelToNameEachImage() {
        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[
                  {"key":"clothing","label":"上传服装图"},
                  {"key":"model","label":"上传模特参考图"}
                ]}
                """);

        String block = service.imageLegendBlock(tool);

        assertTrue(block.contains("第 1 张：上传服装图"), block);
        assertTrue(block.contains("第 2 张：上传模特参考图"), block);
        // 只是列出图片还不够，必须要求模型在提示词里明确指代
        assertTrue(block.contains("第一张图"), block);
        assertTrue(block.contains("第二张图"), block);
    }

    @Test
    void legendCountsWhatWasActuallyUploadedNotWhatTheSchemaDeclares() {
        // 两个槽位的工具，用户只传了 1 张图（智能体自动调用或直接调接口时会发生）。
        // 图例如果说「上传了 2 张」，模型就会去找一个并不存在的第二张图。
        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[
                  {"key":"base","label":"场景底图"},
                  {"key":"subject","label":"要放入的主体"}
                ]}
                """);

        String oneImage = service.imageLegendBlock(tool, 1);

        assertTrue(oneImage.contains("上传了 1 张图片"), oneImage);
        assertTrue(oneImage.contains("第 1 张：场景底图"), oneImage);
        assertFalse(oneImage.contains("第 2 张"), oneImage);
        assertFalse(oneImage.contains("要放入的主体"), oneImage);
    }

    @Test
    void legendNeverExceedsTheSchemaEvenWhenExtraImagesArrive() {
        // 反向：用户传多了。多出来的图没有槽位说明，也不能凭空给它编一个「第 3 张」。
        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[{"key":"image","label":"上传图片"}]}
                """);

        String block = service.imageLegendBlock(tool, 4);

        assertTrue(block.contains("上传了 1 张图片"), block);
        assertFalse(block.contains("第 2 张"), block);
    }

    @Test
    void zeroImagesProducesNoLegendRatherThanAnEmptyCount() {
        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[{"key":"image","label":"上传图片"}]}
                """);

        assertEquals("", service.imageLegendBlock(tool, 0));
    }

    @Test
    void toolsWithoutSchemaProduceNoLegend() {
        CommerceToolEntity tool = toolWithSchema(null);

        assertTrue(service.uploadLegends(tool).isEmpty());
        assertEquals("", service.imageLegendBlock(tool));
        // 没有图片说明时，兜底路径原样返回用户提示词，不该多出空段落
        assertEquals("把背景换成纯白", service.withImageLegend("把背景换成纯白", List.of()));
    }

    @Test
    void fallbackPromptCarriesTheLegend() {
        String prompt = service.withImageLegend(
                "生成模特试穿效果", List.of("上传服装图", "上传模特参考图"));

        assertTrue(prompt.startsWith("生成模特试穿效果"), prompt);
        assertTrue(prompt.contains("第 1 张：上传服装图"), prompt);
        assertTrue(prompt.contains("第 2 张：上传模特参考图"), prompt);
    }

    @Test
    void malformedSchemaIsIgnoredInsteadOfFailingTheRequest() {
        CommerceToolEntity tool = toolWithSchema("{ this is not json ");

        assertTrue(service.uploadLegends(tool).isEmpty());
        assertFalse(service.imageLegendBlock(tool).contains("第 1 张"));
    }

    /* --------------------------- 提示词允许留空 --------------------------- */

    @Test
    void blankPromptStillSendsANonEmptyUserMessage() {
        // 空 content 会被部分供应商直接拒绝，留空时必须换成一句明确意图
        assertEquals(CommerceComposeChatService.DEFAULT_REQUEST, service.userText(null));
        assertEquals(CommerceComposeChatService.DEFAULT_REQUEST, service.userText("   "));
        assertEquals("把背景换成纯白", service.userText("  把背景换成纯白  "));
    }

    @Test
    void blankPromptFallsBackToTheToolBaselinePlusParams() {
        CommerceToolEntity tool = toolWithSchema(null);
        tool.setPrompt("生成自然模特试穿效果，保持商品版型、颜色和材质准确。");

        String prompt = service.fallbackPrompt(tool, "", """
                [{"label":"模特类型","value":"女模"},{"label":"背景","value":"纯色"}]
                """);

        assertTrue(prompt.startsWith("生成自然模特试穿效果"), prompt);
        assertTrue(prompt.contains("模特类型：女模"), prompt);
        assertTrue(prompt.contains("背景：纯色"), prompt);
    }

    @Test
    void paramsSurviveTheFallbackPathEvenWhenTheUserWroteAPrompt() {
        // 兜底路径绕过了对话模型，参数不会有人替它写进提示词，少了就等于用户白选
        CommerceToolEntity tool = toolWithSchema(null);

        String prompt = service.fallbackPrompt(tool, "把背景换成纯白", """
                [{"label":"背景类型","value":"专业影棚"}]
                """);

        assertTrue(prompt.startsWith("把背景换成纯白"), prompt);
        assertTrue(prompt.contains("背景类型：专业影棚"), prompt);
    }

    @Test
    void extraRequirementIsAddedWithoutChangingTheUserPromptBranch() {
        CommerceToolEntity tool = toolWithSchema(null);
        tool.setPrompt("按上传图片生成商品展示图");

        String withExtra = service.fallbackPrompt(tool, "", null, "保留包装上的文字");
        String withoutExtra = service.fallbackPrompt(tool, "", null, " ");

        assertTrue(withExtra.contains("补充要求：\n保留包装上的文字"), withExtra);
        assertEquals(service.fallbackPrompt(tool, "", null), withoutExtra);
    }

    @Test
    void fallbackUsesTheDescriptionWhenTheBaselineIsEmpty() {
        CommerceToolEntity tool = toolWithSchema(null);
        tool.setDescription("生成模特试穿和佩戴效果");

        assertEquals("生成模特试穿和佩戴效果", service.fallbackPrompt(tool, null, null));
    }

    /* ---------------------- 空需求不经过对话模型 ---------------------- */

    /**
     * 留空时不交给对话模型。
     *
     * <p>线上症状：人像抠图传一张人像、什么也不写、点生成，拿回来的是一张风景图。
     * 原因是 systemPrompt 假定「用户给出了需求」，留空时模型把它当成纯文生图请求，
     * 上传的图被彻底忽略。直连路径同时省掉一次模型往返。</p>
     */
    @Test
    void blankPromptGoesStraightToTheImageApiWithoutTheChatModel() {
        CommerceToolRepository tools = mock(CommerceToolRepository.class);
        SmartEditImageService imageService = mock(SmartEditImageService.class);
        LlmClient llmClient = mock(LlmClient.class);

        CommerceToolEntity tool = toolWithSchema("""
                {"uploads":[{"key":"subject","label":"上传要抠图的图片"}]}
                """);
        tool.setPrompt("抠出图中的人物，发丝级边缘，输出背景纯白。");
        tool.setScene(CommerceToolEntity.SCENE_PAINTING);

        when(tools.findFirstBySiteIdAndToolKeyAndStatus(any(), eq("virtual-model"), any()))
                .thenReturn(Optional.of(tool));
        when(imageService.compose(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ImageUrlResponse("/api/v1/files/1/content", null));

        CommerceComposeChatService subject = new CommerceComposeChatService(
                tools, imageService, llmClient, null, null, null, new ObjectMapper(), null);

        List<ChatStreamEvent> events = new ArrayList<>();
        subject.compose(1L, new CurrentUser(6L, 1L, "qa-paint"), "virtual-model",
                List.of(mock(MultipartFile.class)), "   ", null, null, null, events::add);

        verifyNoInteractions(llmClient);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageService).compose(
                any(), any(), promptCaptor.capture(), isNull(), isNull(), eq(FileSource.PAINTING));
        assertTrue(promptCaptor.getValue().startsWith("抠出图中的人物"), promptCaptor.getValue());
        assertTrue(promptCaptor.getValue().contains("第 1 张"), promptCaptor.getValue());

        assertTrue(events.stream().anyMatch(e -> ChatStreamEvent.EVENT_TOOL_RESULT.equals(e.event())),
                "直连路径同样要把出图事件推给前端");
    }

    /** 写了需求就还是走润色，不能被上面的捷径顺手改掉。 */
    @Test
    void writtenPromptStillGoesThroughTheChatModel() {
        CommerceToolRepository tools = mock(CommerceToolRepository.class);
        SmartEditImageService imageService = mock(SmartEditImageService.class);
        LlmClient llmClient = mock(LlmClient.class);

        CommerceToolEntity tool = toolWithSchema(null);
        when(tools.findFirstBySiteIdAndToolKeyAndStatus(any(), eq("virtual-model"), any()))
                .thenReturn(Optional.of(tool));

        CommerceComposeChatService subject = new CommerceComposeChatService(
                tools, imageService, llmClient, null, null, null, new ObjectMapper(), null);

        // 这里的模型解析是 null，只要它还尝试调用对话模型就会抛 NPE —— 正好证明没走捷径
        assertThrows(NullPointerException.class, () -> subject.compose(
                1L, new CurrentUser(6L, 1L, "qa-paint"), "virtual-model",
                List.of(mock(MultipartFile.class)), "把背景换成纯白", null, null, null, event -> {
                }));

        verifyNoInteractions(imageService);
    }

    private CommerceToolEntity toolWithSchema(String schema) {
        CommerceToolEntity tool = CommerceToolEntity.newTool();
        tool.setToolKey("virtual-model");
        tool.setCategory("test");
        tool.setName("虚拟模特试穿");
        tool.setForm("try-on");
        tool.setFormSchema(schema);
        return tool;
    }
}

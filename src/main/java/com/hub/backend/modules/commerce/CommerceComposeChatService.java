package com.hub.backend.modules.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.agent.tool.ToolCallCollector;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.appconfig.ModuleChatModelService;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelFallbackInvoker;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.smartedit.SmartEditImageService;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 电商工具的「对话式生成」。
 *
 * <p>表单填完不是直接提交给图像模型，而是先把参数交给对话模型，由它补全/润色提示词，再以
 * 工具调用的形式落到 {@link SmartEditImageService#compose}。这样前端拿到的是一段可解释的
 * 过程（模型说明 → 工具调用 → 出图），和智能体对话共用同一套 SSE 事件。</p>
 *
 * <p>兜底很重要：如果模型这一轮没有请求工具（提示词已经足够好、或者模型不配合），就直接用
 * 用户填的提示词执行，绝不能因为模型没按套路走而让用户拿不到图。</p>
 */
@Service
public class CommerceComposeChatService {

    private static final Logger log = LoggerFactory.getLogger(CommerceComposeChatService.class);

    /** 工具名，前端据此显示「图片生成」 */
    public static final String TOOL_NAME = "compose_image";

    private static final int MAX_PROMPT = 4000;
    private static final int MAX_ROUNDS = 2;

    /**
     * 用户没写要求时送进对话模型的那句话。
     *
     * <p>不能送空串：部分供应商对空的 user content 直接 400，而这条路径本身是合法的
     * ——参考图和参数已经足够表达需求。这句话只负责让消息非空，真正的内容来自
     * system prompt 里的基准提示词与参数。</p>
     */
    static final String DEFAULT_REQUEST = "没有额外要求，请按基准提示词生成一张图片。";

    private final CommerceToolRepository tools;
    private final SmartEditImageService imageService;
    private final LlmClient llmClient;
    private final ModelUseResolver modelResolver;
    private final ModuleChatModelService moduleChatModels;
    private final UserRelayCredentialResolver relayCredentials;
    private final CommerceGenerationHistoryService historyService;
    private final ObjectMapper objectMapper;

    public CommerceComposeChatService(CommerceToolRepository tools,
                                      SmartEditImageService imageService,
                                      LlmClient llmClient,
                                      ModelUseResolver modelResolver,
                                      ModuleChatModelService moduleChatModels,
                                      UserRelayCredentialResolver relayCredentials,
                                      ObjectMapper objectMapper,
                                      CommerceGenerationHistoryService historyService) {
        this.tools = tools;
        this.imageService = imageService;
        this.llmClient = llmClient;
        this.modelResolver = modelResolver;
        this.moduleChatModels = moduleChatModels;
        this.relayCredentials = relayCredentials;
        this.objectMapper = objectMapper;
        this.historyService = historyService;
    }

    /** 一次完整的「模型润色 → 出图」流程，事件通过 {@code publisher} 推给前端。 */
    public void compose(Long siteId,
                        CurrentUser currentUser,
                        String toolKey,
                        List<MultipartFile> images,
                        String prompt,
                        String size,
                        String paramsJson,
                        String extraRequirement,
                        Consumer<ChatStreamEvent> publisher) {
        if (images == null || images.isEmpty()) {
            throw new InvalidRequestException("images must not be empty");
        }
        String extra = extraRequirement == null ? "" : extraRequirement.strip();
        if (extra.length() > 1000) {
            extra = extra.substring(0, 1000);
        }
        // prompt 允许留空：参考图与表单参数本身已经能表达需求，缺一句话不该让整次生成失败
        // （留空时走下面的直连出图分支，不经过对话模型）

        CommerceToolEntity tool = tools
                .findFirstBySiteIdAndToolKeyAndStatus(siteId, toolKey, CommerceToolEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("Tool '%s' was not found".formatted(toolKey)));

        if (prompt == null || prompt.isBlank()) {
            // 留空时不要让对话模型插手。systemPrompt 的前提是「用户已经给出了需求」，留空时这个
            // 前提不成立，模型会当成一次纯文生图请求自行发挥 —— 实测人像抠图/智能抠图/3D 卡通/
            // 更换背景/场景变换会返回一张与上传图完全无关的风景图。基准提示词 + 勾选参数 +
            // 上传说明本身已经足够表达需求，直接出图既忠实，也省掉一次模型往返。
            String directId = "compose_direct_" + System.nanoTime();
            publisher.accept(ChatStreamEvent.toolCall(directId, TOOL_NAME, "{}"));
            produce(currentUser, tool, images, fallbackPrompt(tool, prompt, paramsJson, extra), size, directId, publisher);
            publisher.accept(ChatStreamEvent.completed(""));
            return;
        }

        ProviderCredentials credentials = relayCredentials.resolve(currentUser);
        List<ModelUseResolver.Resolved> models = chatModels(siteId);

        String request = userText(prompt);
        // 模型没调工具时直接用用户原文，那条路径拿不到 systemPrompt 里的图片说明，这里补一句
        String fallbackPrompt = fallbackPrompt(tool, prompt, paramsJson, extra);
        List<LlmChatMessage> messages = new ArrayList<>();
        messages.add(LlmChatMessage.system(systemPrompt(tool, paramsJson, images.size(), extra)));
        messages.add(LlmChatMessage.user(request));

        List<LlmToolDefinition> declared = List.of(definition());
        boolean produced = false;

        for (int round = 0; round < MAX_ROUNDS; round += 1) {
            List<LlmToolDefinition> offered = round == MAX_ROUNDS - 1 ? List.of() : declared;
            ModelFallbackInvoker.Result<ToolCallCollector> attempt =
                    ModelFallbackInvoker.stream(
                            models,
                            (model, markPublished) -> {
                                ToolCallCollector collector = new ToolCallCollector();
                                llmClient.stream(
                                        new LlmChatCommand(model.upstreamKey(), messages, null, null,
                                                // 回退链里每个候选模型可能属于不同分组
                                                relayCredentials.resolve(currentUser, model.id()),
                                                offered),
                                        chunk -> {
                                            if (chunk.done()) return;
                                            if (chunk.hasContent()) {
                                                markPublished.run();
                                                publisher.accept(ChatStreamEvent.delta(
                                                        chunk.contentDelta()));
                                            }
                                            if (chunk.hasToolCalls()) {
                                                collector.accept(chunk.toolCallDeltas());
                                            }
                                        });
                                return collector;
                            },
                            ignored -> {
                            });
            ToolCallCollector collector = attempt.value();
            int selectedIndex = models.indexOf(attempt.model());
            models = models.subList(selectedIndex, models.size());

            if (!collector.hasCalls()) {
                if (!produced) {
                    // 模型没调工具：自己执行一次，保证用户始终拿得到图
                    String fallbackId = "compose_fallback_" + System.nanoTime();
                    publisher.accept(ChatStreamEvent.toolCall(fallbackId, TOOL_NAME, "{}"));
                    produced = produce(currentUser, tool, images, fallbackPrompt, size, fallbackId, publisher);
                }
                break;
            }

            List<LlmToolCall> calls = collector.calls();
            messages.add(LlmChatMessage.assistantToolCalls(calls));
            for (LlmToolCall call : calls) {
                String refined = refine(call, fallbackPrompt);
                publisher.accept(ChatStreamEvent.toolCall(call.id(), TOOL_NAME, call.arguments()));
                produced = produce(currentUser, tool, images, refined, size, call.id(), publisher) || produced;
                messages.add(LlmChatMessage.toolResult(
                        call.id(),
                        produced ? "图片已生成，已通过界面展示给用户。" : "图片生成失败，请提示用户重试。"));
            }
        }

        publisher.accept(ChatStreamEvent.completed(""));
    }

    /**
     * 工具产出的附件该记到哪个来源。
     *
     * <p>电商工具保持 {@code SMART_EDIT}：它们有独立的历史表（commerce_generation_history），
     * 前台不按附件来源列作品，改它只会影响别处的统计口径。绘画工具库不一样 ——
     * 用户从 `/painting` 点进来的，作品就该出现在绘画页。</p>
     */
    static FileSource sourceOf(CommerceToolEntity tool) {
        return CommerceToolEntity.SCENE_PAINTING.equals(tool.getScene())
                ? FileSource.PAINTING
                : FileSource.SMART_EDIT;
    }

    /** 调一次图像接口并把结果推给前端，返回是否成功 */
    private boolean produce(CurrentUser currentUser,
                            CommerceToolEntity tool,
                            List<MultipartFile> images,
                            String prompt,
                            String size,
                            String callId,
                            Consumer<ChatStreamEvent> publisher) {
        try {
            // 产出归属跟着工具场景走：绘画工具库（/painting）的图要进绘画作品，
            // 否则用户从绘画页点的抠图会跑到「修图成果」里。
            ImageUrlResponse result = imageService.compose(
                    currentUser, images, prompt, null, size, sourceOf(tool));
            if (historyService != null) {
                try {
                    historyService.record(currentUser, tool.getToolKey(), tool.getName(), prompt, result.url());
                } catch (RuntimeException historyFailure) {
                    log.warn("Unable to persist commerce generation history for tool {}: {}",
                            tool.getToolKey(), historyFailure.getMessage());
                }
            }
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("url", result.url());
            Map<String, Object> meta = Map.of("images", List.of(image));
            publisher.accept(ChatStreamEvent.toolResult(
                    callId, TOOL_NAME, true, "{\"images\":[{\"url\":\"" + result.url() + "\"}]}", meta));
            return true;
        } catch (RuntimeException failure) {
            log.warn("commerce compose failed for tool prompt: {}", failure.getMessage());
            // 只给原因，不加「图片生成失败：」前缀 —— 工作台的失败卡片自己就有那个标签，
            // 加了会出现「图片生成失败 / 图片生成失败：非常抱歉…」这样的重复。
            String upstreamCode = failure instanceof LlmInvocationException llm ? llm.getUpstreamCode() : null;
            String readable = UpstreamFailureText.describe(
                    failure.getMessage(), upstreamCode, "请稍后再试");
            publisher.accept(ChatStreamEvent.toolResult(
                    callId, TOOL_NAME, false, readable, Map.of()));
            return false;
        }
    }

    /** 从工具参数里取模型润色后的提示词，取不到就用用户原文 */
    private String refine(LlmToolCall call, String fallback) {
        String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        try {
            JsonNode args = objectMapper.readTree(raw);
            String prompt = args.path("prompt").asText("");
            return prompt.isBlank() ? fallback : truncate(prompt.strip());
        } catch (JsonProcessingException malformed) {
            log.warn("compose tool arguments were not valid JSON: {}", malformed.getOriginalMessage());
            return fallback;
        }
    }

    private static String truncate(String prompt) {
        return prompt.length() > MAX_PROMPT ? prompt.substring(0, MAX_PROMPT) : prompt;
    }

    private LlmToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "最终提交给图像模型的提示词：主体、场景、光线、风格与需要保持不变的部分"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("prompt"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "按照润色后的提示词执行图片编辑/合成。必须调用一次，不要询问用户，也不要只输出文字。",
                schema);
    }

    private String systemPrompt(CommerceToolEntity tool, String paramsJson, int availableImages,
                                String extraRequirement) {
        String params = describeParams(paramsJson);
        String legends = imageLegendBlock(tool, availableImages);
        String extra = extraRequirement == null || extraRequirement.isBlank()
                ? "" : "\n用户补充要求（务必体现在提示词里）：\n" + extraRequirement;
        return """
                你是电商图片工具「%s」的执行助手。用户已经上传了参考图并给出了需求，请：
                1. 结合下面的基准提示词、用户勾选的参数与用户要求，写出一段更具体、更适合图像模型的中文提示词；
                2. 立即调用 %s 工具执行一次，把提示词放进 prompt 参数；
                3. 不要反问、不要要求用户补充信息，不要输出代码块。
                回复保持简短（一到两句），把版面留给生成的图片。

                工具说明：%s
                基准提示词：%s
                %s%s%s
                """.formatted(
                tool.getName(),
                TOOL_NAME,
                tool.getDescription() == null ? "" : tool.getDescription(),
                tool.getPrompt() == null ? "" : tool.getPrompt(),
                legends,
                params.isBlank() ? "" : "\n用户勾选的参数（务必体现在提示词里）：\n" + params,
                extra);
    }

    /**
     * 上传槽的顺序说明。
     *
     * <p>这一段的由来：原先系统提示词只给了工具描述和基准提示词（例如「生成自然模特试穿效果」），
     * 对话模型并不知道第 1 张是服装、第 2 张是模特，润色出来的提示词自然也不会区分二者。
     * 图像模型拿到两张图和一句笼统的「生成试穿效果」，最省力的做法就是重绘其中一张 ——
     * 表现出来就是「出了图，但衣服没穿上」。</p>
     *
     * <p>顺序来自 {@code form_schema.uploads}，与前端 {@code slots} 的提交顺序一致；
     * 文案取自运营在后台配的 label / hint，所以改工具不用改这里。</p>
     */
    List<String> uploadLegends(CommerceToolEntity tool) {
        String raw = tool.getFormSchema();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode uploads = objectMapper.readTree(raw).path("uploads");
            if (!uploads.isArray()) {
                return List.of();
            }
            List<String> legends = new ArrayList<>();
            for (JsonNode slot : uploads) {
                String label = slot.path("label").asText("").strip();
                String hint = slot.path("hint").asText("").strip();
                if (label.isBlank()) {
                    continue;
                }
                legends.add(hint.isBlank() ? label : label + "（" + hint + "）");
            }
            return legends;
        } catch (JsonProcessingException malformed) {
            log.warn("commerce tool {} has malformed form_schema: {}",
                    tool.getToolKey(), malformed.getOriginalMessage());
            return List.of();
        }
    }

    String imageLegendBlock(CommerceToolEntity tool) {
        return imageLegendBlock(tool, Integer.MAX_VALUE);
    }

    /**
     * 上传槽的顺序说明。
     *
     * <p>{@code availableImages} 是这次请求**实际收到**的图片数，图例按
     * {@code min(槽位数, 实际张数)} 生成。</p>
     *
     * <p>原先一律按 {@code form_schema.uploads} 的条数写「用户按以下顺序上传了 N 张图片」，
     * 不看真实张数。图片少于槽位时，提示词会断言有 2 张而模型只拿到 1 张 ——
     * 它要么把唯一那张当成「第 1 张」，要么自己编一个第二张出来。
     * 前端对多槽工具已经强制填满（缺槽会禁用提交并提示「还差 …」），
     * 但智能体自动调用和直接调接口不受这条约束，所以后端自己也要说实话。</p>
     */
    String imageLegendBlock(CommerceToolEntity tool, int availableImages) {
        List<String> legends = uploadLegends(tool);
        if (legends.isEmpty()) {
            return "";
        }
        int usable = Math.max(0, Math.min(legends.size(), availableImages));
        if (usable == 0) {
            return "";
        }
        StringBuilder block = new StringBuilder()
                .append("\n用户按以下顺序上传了 ").append(usable).append(" 张图片：\n");
        for (int index = 0; index < usable; index++) {
            block.append("- 第 ").append(index + 1).append(" 张：").append(legends.get(index)).append('\n');
        }
        block.append("写提示词时必须用「第一张图 / 第二张图」明确指代每张图，讲清楚哪张是主体、")
                .append("哪张是参考，以及要把哪张图的什么东西应用到哪张图上。\n");
        return block.toString();
    }

    /** 兜底路径用：模型没调工具时，直接在用户原文后面补一段参考图说明 */
    String withImageLegend(String prompt, List<String> legends) {
        if (legends.isEmpty()) {
            return prompt;
        }
        StringBuilder text = new StringBuilder(prompt).append("\n\n参考图说明：");
        for (int index = 0; index < legends.size(); index++) {
            if (index > 0) {
                text.append("；");
            }
            text.append("第 ").append(index + 1).append(" 张：").append(legends.get(index));
        }
        return truncate(text.toString());
    }

    /** 送进对话模型的用户消息：留空时给一句固定意图，保证消息非空 */
    String userText(String prompt) {
        String trimmed = prompt == null ? "" : prompt.strip();
        return trimmed.isEmpty() ? DEFAULT_REQUEST : truncate(trimmed);
    }

    /**
     * 兜底路径（模型没调工具、或调了但没给 prompt）真正提交给图像模型的提示词。
     *
     * <p>三个来源按顺序叠加：用户原文（留空则退回工具基准提示词）→ 用户勾选的参数 →
     * 上传槽说明。后两段不能省：这条路径绕过了对话模型，参数不会有人替它写进提示词，
     * 少了就等于用户白选。</p>
     */
    String fallbackPrompt(CommerceToolEntity tool, String prompt, String paramsJson) {
        return fallbackPrompt(tool, prompt, paramsJson, null);
    }

    String fallbackPrompt(CommerceToolEntity tool, String prompt, String paramsJson, String extraRequirement) {
        String trimmed = prompt == null ? "" : prompt.strip();
        String base = trimmed.isEmpty() ? defaultPrompt(tool) : trimmed;
        String withExtra = extraRequirement == null || extraRequirement.isBlank()
                ? base : base + "\n补充要求：\n" + extraRequirement.strip();
        return truncate(withImageLegend(withParams(withExtra, paramsJson), uploadLegends(tool)));
    }

    /** 工具默认描述：优先用运营配的基准提示词，其次工具简介，都没有时给一句通用话术 */
    private String defaultPrompt(CommerceToolEntity tool) {
        String baseline = tool.getPrompt() == null ? "" : tool.getPrompt().strip();
        if (!baseline.isEmpty()) {
            return baseline;
        }
        String description = tool.getDescription() == null ? "" : tool.getDescription().strip();
        return description.isEmpty() ? "生成一张高质量的商品图片" : description;
    }

    /** 把勾选参数摊成「参考参数」段，便于直接喂给图像模型 */
    String withParams(String prompt, String paramsJson) {
        String params = describeParams(paramsJson);
        return params.isBlank() ? prompt : prompt + "\n参考参数：\n" + params;
    }

    /**
     * 把前端提交的 {@code [{"label":"成套类型","value":"美妆个护套装"}]} 摊成给模型看的多行文本。
     *
     * <p>解析失败就当没填——参数只是辅助信息，不该因为它让整次生成失败。</p>
     */
    private String describeParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            if (!root.isArray()) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : root) {
                String label = item.path("label").asText("");
                String value = item.path("value").asText("");
                if (label.isBlank() || value.isBlank()) {
                    continue;
                }
                text.append("- ").append(label).append("：").append(value).append('\n');
            }
            return text.toString().strip();
        } catch (JsonProcessingException malformed) {
            log.warn("commerce params were not valid JSON: {}", malformed.getOriginalMessage());
            return "";
        }
    }

    /**
     * 润色对话用哪些模型：走站点的「当前有效默认」及同版本降级链，不用静态配置。
     *
     * <p>曾经这里传的是 {@code llmProperties.getDefaultModel()}，模型目录一改名（运营把
     * {@code deepseek-v4.1-flash} 换成 {@code deepseek-v4-1-flash-260910}）电商生成就整片 404，
     * 而同期的 /app/config 已经知道正确值 —— 因为只有智能体对话接上了自适应解析。</p>
     */
    List<ModelUseResolver.Resolved> chatModels(Long siteId) {
        return moduleChatModels.resolveAllForSite(siteId, ModuleChatModelService.COMMERCE);
    }
}

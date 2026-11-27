package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.image.ImageGenerationService;
import com.hub.backend.modules.image.dto.GeneratedImageResponse;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片生成工具：把「生成一张图」暴露给模型。
 *
 * <p>复用 {@link ImageGenerationService}，所以结果与 {@code POST /api/v1/images/generations}
 * 完全一致 —— 同样落库为平台附件、同样走图片模型的凭据与配额，不另造一条生图链路。</p>
 *
 * <p>模型 key / 张数 / 尺寸 / 作品归属由 {@link ToolContext} 注入，不写进工具必填参数，避免模型乱选贵模。</p>
 */
@Component
public class ImageGenerationTool implements AgentTool {

    public static final String TOOL_NAME = "generate_image";

    private static final int MAX_PROMPT = 4000;
    private static final List<String> ALLOWED_SIZES = List.of(
            "1024x1024", "1024x1536", "1536x1024", "1792x1024", "1024x1792", "auto");
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTool.class);

    private final ImageGenerationService imageGenerationService;
    private final ObjectMapper objectMapper;

    public ImageGenerationTool(ImageGenerationService imageGenerationService,
                               ObjectMapper objectMapper) {
        this.imageGenerationService = imageGenerationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "要画什么，优先直接用用户的原话。用户只说了一两个词时，可补光线/视角/景深/画质/色调这五项；"
                        + "用户已经说清楚的部分不要润色、不要改写，也不要新增用户没提到的任何具象元素"
                        + "（人物、动物、服装、装饰、道具、背景、文字、品牌）。"
                        + "用户说的颜色、数量、图中文字、否定要求必须原样保留。"
                        + "只描述一张图里的内容，不要写「四张」「多格」。"
                        + "若用户是在修改已有图片（换背景、改颜色、加元素等），请改用 edit_image，不要调用本工具。"));
        properties.put("size", Map.of(
                "type", "string",
                "enum", ALLOWED_SIZES,
                "description", "图片尺寸。通常由系统按用户创作设置注入，可不填；缺省 1024x1024"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("prompt"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "根据文字描述生成全新图片（可一次多张）。用户要求画图、生成插画、制作商品主图或海报，"
                        + "且不是在修改会话里已有图片时调用。",
                schema);
    }

    /** Image generation is an explicit capability; do not expose it to every legacy agent. */
    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        CurrentUser currentUser = context.currentUser();
        String prompt;
        String sizeFromModel;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            JsonNode args = objectMapper.readTree(raw);
            prompt = args.path("prompt").asText("");
            sizeFromModel = textOrNull(args.path("size"));
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed("工具参数不是合法 JSON：" + malformed.getOriginalMessage());
        }

        if (prompt.isBlank()) {
            return ToolOutcome.failed("缺少 prompt 参数，请给出图片描述");
        }
        if (prompt.length() > MAX_PROMPT) {
            prompt = prompt.substring(0, MAX_PROMPT);
        }

        // 用户创作设置优先于模型乱填的 size
        String size = resolveSize(context.imageSize(), sizeFromModel);
        int count = context.imageCount() == null ? 1 : Math.min(4, Math.max(1, context.imageCount()));
        String source = context.fileSource() == null ? FileSource.AGENT.value() : context.fileSource();

        try {
            ImageGenerationResponse response = imageGenerationService.generate(
                    currentUser,
                    new ImageGenerationRequest(
                            prompt,
                            context.imageModel(),
                            size,
                            null,
                            count,
                            context.conversationId(),
                            source));

            List<Map<String, Object>> images = new ArrayList<>(response.images().size());
            for (GeneratedImageResponse image : response.images()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("url", image.url());
                if (image.fileId() != null) {
                    item.put("fileId", image.fileId());
                }
                if (image.revisedPrompt() != null) {
                    item.put("revisedPrompt", image.revisedPrompt());
                }
                images.add(item);
            }
            if (images.isEmpty()) {
                return ToolOutcome.failed("这次没有生成出图片，请稍后再试");
            }

            String content = objectMapper.writeValueAsString(Map.of("images", images));
            log.info("generate_image produced {} image(s) for user {}", images.size(), currentUser.userId());
            return ToolOutcome.ok(content, Map.of("images", images));
        } catch (Exception failure) {
            log.warn("generate_image failed: {}", failure.getMessage());
            String upstreamCode = failure instanceof LlmInvocationException llm ? llm.getUpstreamCode() : null;
            // 这句话有两处消费者：模型（会转述成正文）和前端失败事件。只说「没出图」，
            // 不出现服务商 / 网关 / 错误码等内部链路词汇 —— 用户不该知道我们背后还有中转。
            return ToolOutcome.failed("这次没有生成出图片：" + UpstreamFailureText.describe(
                    failure.getMessage(), upstreamCode, "请稍后再试"));
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /** Prefer user/session size; fall back to model arg; ignore unknown values. */
    static String resolveSize(String preferred, String fromModel) {
        String chosen = firstAllowed(preferred);
        if (chosen != null) {
            return chosen;
        }
        chosen = firstAllowed(fromModel);
        return chosen != null ? chosen : "1024x1024";
    }

    private static String firstAllowed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        for (String allowed : ALLOWED_SIZES) {
            if (allowed.equalsIgnoreCase(value)) {
                return allowed.equals("auto") ? "auto" : allowed;
            }
        }
        return null;
    }
}

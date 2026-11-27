package com.hub.backend.modules.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST /api/v1/chat/completions} and {@code POST /api/v1/chat/stream}.
 */
@Schema(name = "ChatCompletionRequest", description = "对话请求体，两个对话接口共用")
public record ChatCompletionRequest(
        @Schema(description = "模型目录中的模型 key，缺省取服务端默认模型", example = "deepseek-v4.1-flash")
        @Size(max = 128, message = "model must be at most 128 characters")
        String model,

        @Schema(description = "上下文消息，按时间正序，至少包含一条 user 消息",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "messages must not be empty")
        @Size(max = 100, message = "messages must contain at most 100 entries")
        @Valid
        List<ChatMessagePayload> messages,

        @Schema(description = "采样温度，越大越随机", example = "0.7")
        @DecimalMin(value = "0.0", message = "temperature must be greater than or equal to 0")
        @DecimalMax(value = "2.0", message = "temperature must be less than or equal to 2")
        Double temperature,

        @Schema(description = "生成的最大 token 数", example = "512")
        @Min(value = 1, message = "maxTokens must be at least 1")
        @Max(value = 32768, message = "maxTokens must be at most 32768")
        Integer maxTokens,

        @Schema(description = "追加到已有会话的 ID，缺省则自动新建会话", example = "12")
        Long conversationId,

        @Schema(description = "会话标题，仅在新建会话时生效；缺省由首条消息派生",
                example = "第一次对话")
        /*
         * 上限与 content 对齐，不再卡 255：客户端习惯把用户这条输入原样当标题传，
         * 一条长 prompt 上千字很常见。卡在 255 会让整轮对话在入口就被判 400
         * （用户只看到「请求参数有误」），而标题只是会话列表里的一行字。
         * 真正落库由 ConversationEntity#normalizeTitle 截断到列宽。
         */
        @Size(max = 32000, message = "title must be at most 32000 characters")
        String title,

        @Schema(description = "是否开启联网搜索。为 true 时模型可调用 web_search 工具查询实时网页",
                example = "false")
        Boolean webSearch,

        @Schema(description = "是否请求已声明支持思考能力的模型输出 reasoning", example = "false")
        Boolean thinking,

        @Schema(description = "对已有 assistant 消息重新生成：软隐藏该条及之后，新回答作为同组新版本",
                example = "42")
        Long regenerateOfMessageId,

        @Schema(description = "编辑已有 user 消息并重发：软隐藏该条及之后，新提问作为同组新版本",
                example = "41")
        Long editOfMessageId,

        @Schema(description = "调用工作区标识；AI 教育工作区传 ai-education")
        String workspace,

        @Schema(description = "可选：本轮生图使用的 IMAGE 模型 key；缺省走站点默认图模",
                example = "gpt-image-2")
        @Size(max = 128, message = "imageModel must be at most 128 characters")
        String imageModel,

        @Schema(description = "可选：文生图张数 1–4，缺省 1", example = "2")
        @Min(value = 1, message = "imageCount must be at least 1")
        @Max(value = 4, message = "imageCount must be at most 4")
        Integer imageCount,

        @Schema(description = "可选：改图焦点附件 ID（用户点了「以此图继续」）；缺省用会话最新生成图",
                example = "88")
        Long focusFileId,

        @Schema(description = "可选：文生图尺寸 WxH 或 auto；缺省由上游/工具默认",
                example = "1024x1024")
        @Size(max = 32, message = "imageSize must be at most 32 characters")
        @Pattern(regexp = "auto|[1-9][0-9]{0,4}x[1-9][0-9]{0,4}",
                message = "imageSize must be 'auto' or a WxH pair")
        String imageSize
) {
    public ChatCompletionRequest(String model, List<ChatMessagePayload> messages, Double temperature,
                                 Integer maxTokens, Long conversationId, String title,
                                 Boolean webSearch, Boolean thinking, Long regenerateOfMessageId,
                                 Long editOfMessageId) {
        this(model, messages, temperature, maxTokens, conversationId, title, webSearch, thinking,
                regenerateOfMessageId, editOfMessageId, null, null, null, null, null);
    }

    public ChatCompletionRequest(String model, List<ChatMessagePayload> messages, Double temperature,
                                 Integer maxTokens, Long conversationId, String title,
                                 Boolean webSearch, Boolean thinking, Long regenerateOfMessageId,
                                 Long editOfMessageId, String workspace) {
        this(model, messages, temperature, maxTokens, conversationId, title, webSearch, thinking,
                regenerateOfMessageId, editOfMessageId, workspace, null, null, null, null);
    }

    /** Convenience for callers that do not care about web search. */
    public ChatCompletionRequest(String model,
                                 List<ChatMessagePayload> messages,
                                 Double temperature,
                                 Integer maxTokens,
                                 Long conversationId,
                                 String title) {
        this(model, messages, temperature, maxTokens, conversationId, title, null, null, null, null,
                null, null, null, null, null);
    }

    public ChatCompletionRequest(String model,
                                 List<ChatMessagePayload> messages,
                                 Double temperature,
                                 Integer maxTokens,
                                 Long conversationId,
                                 String title,
                                 Boolean webSearch) {
        this(model, messages, temperature, maxTokens, conversationId, title, webSearch, null, null, null,
                null, null, null, null, null);
    }

    public ChatCompletionRequest(String model,
                                 List<ChatMessagePayload> messages,
                                 Double temperature,
                                 Integer maxTokens,
                                 Long conversationId,
                                 String title,
                                 Boolean webSearch,
                                 Boolean thinking) {
        this(model, messages, temperature, maxTokens, conversationId, title, webSearch, thinking, null, null,
                null, null, null, null, null);
    }

    public boolean webSearchEnabled() {
        return Boolean.TRUE.equals(webSearch);
    }

    public boolean thinkingEnabled() {
        return Boolean.TRUE.equals(thinking);
    }

    public int imageCountOrDefault() {
        return imageCount == null ? 1 : imageCount;
    }

    public String imageSizeOrNull() {
        return imageSize == null || imageSize.isBlank() ? null : imageSize.strip();
    }
}

package com.hub.backend.modules.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of {@code POST /api/v1/chat/completions}.
 */
@Schema(name = "ChatCompletionResponse", description = "对话响应体")
public record ChatCompletionResponse(
        @Schema(description = "会话 ID，未开启持久化时为 null", example = "1")
        Long conversationId,

        @Schema(description = "落库的 assistant 消息 ID，未开启持久化时为 null", example = "2")
        Long messageId,

        @Schema(description = "模型目录 key", example = "deepseek-v4.1-flash")
        String model,

        @Schema(description = "模型的完整回答", example = "你好，有什么可以帮你？")
        String content,

        @Schema(description = "上游返回的结束原因，正常为 stop", example = "stop")
        String finishReason,

        @Schema(description = "本次调用的 token 用量")
        Usage usage,

        @Schema(description = "上游调用耗时（毫秒）", example = "3022")
        long latencyMs
) {

    @Schema(name = "ChatUsage", description = "token 用量")
    public record Usage(
            @Schema(description = "输入 token 数", example = "15") Integer promptTokens,
            @Schema(description = "输出 token 数", example = "32") Integer completionTokens,
            @Schema(description = "合计 token 数", example = "47") Integer totalTokens) {
    }

    public static Usage usageOf(int promptTokens, int completionTokens, int totalTokens) {
        return new Usage(promptTokens, completionTokens, totalTokens);
    }
}

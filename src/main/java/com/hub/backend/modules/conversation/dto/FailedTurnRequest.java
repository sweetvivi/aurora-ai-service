package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST /api/v1/conversations/{id}/failed-turn}.
 *
 * @param content        user message that was sent
 * @param errorMessage   human-readable failure reason shown as the assistant turn
 * @param attachmentIds  optional file ids the user had attached (same as chat stream)
 */
@Schema(name = "FailedTurn", description = "记录一次生成失败的对话轮次")
public record FailedTurnRequest(
        @Schema(description = "用户消息正文", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "content must not be blank")
        @Size(max = 32000, message = "content must be at most 32000 characters")
        String content,

        @Schema(description = "失败原因，写入 assistant 消息", example = "调用失败，请稍后重试")
        @Size(max = 2000, message = "errorMessage must be at most 2000 characters")
        String errorMessage,

        @Schema(description = "用户本轮携带的附件 ID，写入历史以便重开会话时回显")
        @Size(max = 5, message = "at most 5 attachments per message")
        List<Long> attachmentIds
) {

    public FailedTurnRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    /** Backward-compatible ctor used by older callers / tests. */
    public FailedTurnRequest(String content, String errorMessage) {
        this(content, errorMessage, List.of());
    }
}

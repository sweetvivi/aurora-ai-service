package com.hub.backend.modules.conversation.dto;

import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.modules.conversation.entity.ChatMessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One stored message of a conversation.
 */
@Schema(name = "ChatMessage", description = "会话消息")
public record ChatMessageResponse(
        @Schema(description = "消息 ID，可作为下一页的 cursor", example = "2") Long id,
        @Schema(description = "会话内序号，从 1 开始", example = "2") int sequenceNo,
        @Schema(description = "同轮版本组 ID；编辑/重生成的兄弟版本共享", example = "2") Long turnGroupId,
        @Schema(description = "组内版本号，从 1 开始", example = "1") int version,
        @Schema(description = "是否为当前展示/喂给模型的版本", example = "true") boolean active,
        @Schema(description = "同组版本总数（含 inactive），用于前端 1/N", example = "1") int versionCount,
        @Schema(description = "消息角色", example = "assistant") String role,
        @Schema(description = "消息正文；失败轮次为错误说明") String content,
        @Schema(description = "推理链正文；无思考时为 null") String thinkingContent,
        @Schema(description = "随消息送进模型的附件引用；空表示没有附件")
        List<ChatAttachmentRef> attachments,
        @Schema(description = "消息状态：COMPLETED / FAILED / CANCELLED", example = "COMPLETED") String status,
        @Schema(description = "输入 token 数，未记录时为 null") Integer promptTokens,
        @Schema(description = "输出 token 数，未记录时为 null") Integer completionTokens,
        @Schema(description = "合计 token 数，未记录时为 null") Integer totalTokens,
        @Schema(description = "失败时的错误码", example = "LLM_INVOCATION_FAILED") String errorCode,
        @Schema(description = "失败时的错误详情", example = "upstream timeout") String errorMessage,
        @Schema(description = "当时使用的模型 key；用户消息或目录已删时为 null", example = "gpt-6-astra")
        String modelKey,
        @Schema(description = "当时使用的模型展示名；无目录行时为 null", example = "GPT-6 Astra")
        String modelName,
        @Schema(description = "创建时间") LocalDateTime createdAt
) {

    public ChatMessageResponse {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public static ChatMessageResponse from(ChatMessageEntity message) {
        return from(message, List.of(), 1, null, null);
    }

    public static ChatMessageResponse from(ChatMessageEntity message, List<ChatAttachmentRef> attachments) {
        return from(message, attachments, 1, null, null);
    }

    public static ChatMessageResponse from(ChatMessageEntity message,
                                          List<ChatAttachmentRef> attachments,
                                          int versionCount) {
        return from(message, attachments, versionCount, null, null);
    }

    public static ChatMessageResponse from(ChatMessageEntity message,
                                          List<ChatAttachmentRef> attachments,
                                          int versionCount,
                                          String modelKey,
                                          String modelName) {
        Long turnGroupId = message.getTurnGroupId() != null ? message.getTurnGroupId() : message.getId();
        return new ChatMessageResponse(
                message.getId(),
                message.getSequenceNo(),
                turnGroupId,
                message.getVersion() <= 0 ? 1 : message.getVersion(),
                message.isActive(),
                Math.max(1, versionCount),
                message.getRole(),
                message.getContent(),
                message.getThinkingContent(),
                attachments,
                message.getStatus(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getErrorCode(),
                message.getErrorMessage(),
                modelKey,
                modelName,
                message.getCreatedAt());
    }
}

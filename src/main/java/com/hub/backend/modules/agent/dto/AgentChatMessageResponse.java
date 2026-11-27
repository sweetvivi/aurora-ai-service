package com.hub.backend.modules.agent.dto;

import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "AgentChatMessage", description = "智能体会话消息")
public record AgentChatMessageResponse(
        Long id,
        int sequenceNo,
        @Schema(description = "版本组 id：同一问（或同一答）的多个版本共用一个组")
        Long turnGroupId,
        @Schema(description = "组内版本号，从 1 开始")
        int version,
        @Schema(description = "组内版本总数；大于 1 时前端显示 1/N")
        int versionCount,
        @Schema(description = "是否是当前显示、并会喂回模型的版本")
        boolean active,
        String role,
        String content,
        String thinkingContent,
        @Schema(description = "本轮发生过的工具调用，按发生顺序；空表示没有调用过")
        List<AgentToolCallRecord> toolCalls,
        @Schema(description = "随消息送进模型的图片，按用户选择顺序；空表示没有附件")
        List<ChatAttachmentRef> attachments,
        String status,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt
) {

    public static AgentChatMessageResponse from(AgentChatMessageEntity message,
                                                List<AgentToolCallRecord> toolCalls,
                                                List<ChatAttachmentRef> attachments) {
        return from(message, toolCalls, attachments, 1);
    }

    public static AgentChatMessageResponse from(AgentChatMessageEntity message,
                                                List<AgentToolCallRecord> toolCalls,
                                                List<ChatAttachmentRef> attachments,
                                                int versionCount) {
        return new AgentChatMessageResponse(
                message.getId(),
                message.getSequenceNo(),
                message.getTurnGroupId(),
                message.getVersion(),
                Math.max(1, versionCount),
                message.isActive(),
                message.getRole(),
                message.getContent(),
                message.getThinkingContent(),
                toolCalls == null ? List.of() : List.copyOf(toolCalls),
                attachments == null ? List.of() : List.copyOf(attachments),
                message.getStatus(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getErrorCode(),
                message.getErrorMessage(),
                message.getCreatedAt());
    }
}

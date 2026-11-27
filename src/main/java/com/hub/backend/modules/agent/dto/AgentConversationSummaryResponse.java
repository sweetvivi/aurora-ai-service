package com.hub.backend.modules.agent.dto;

import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "AgentConversationSummary", description = "智能体会话摘要")
public record AgentConversationSummaryResponse(
        Long id,
        Long agentId,
        Long modelId,
        String title,
        int messageCount,
        boolean pinned,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AgentConversationSummaryResponse from(AgentConversationEntity conversation) {
        return new AgentConversationSummaryResponse(
                conversation.getId(),
                conversation.getAgentId(),
                conversation.getModelId(),
                conversation.getTitle(),
                conversation.getMessageCount(),
                conversation.isPinned(),
                conversation.getLastMessageAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}

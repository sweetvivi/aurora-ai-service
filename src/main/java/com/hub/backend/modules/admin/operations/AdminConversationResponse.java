package com.hub.backend.modules.admin.operations;

import com.hub.backend.modules.conversation.entity.ConversationEntity;

import java.time.LocalDateTime;

public record AdminConversationResponse(
        Long id,
        String username,
        String title,
        String modelId,
        int messageCount,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminConversationResponse from(ConversationEntity conversation,
                                                 String username,
                                                 String modelKey) {
        return new AdminConversationResponse(
                conversation.getId(),
                username == null ? "" : username,
                conversation.getTitle() == null ? "" : conversation.getTitle(),
                modelKey == null ? "" : modelKey,
                conversation.getMessageCount(),
                ConversationEntity.STATUS_ACTIVE.equalsIgnoreCase(conversation.getStatus())
                        ? "active"
                        : "archived",
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}

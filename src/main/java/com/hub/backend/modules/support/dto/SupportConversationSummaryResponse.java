package com.hub.backend.modules.support.dto;

import java.time.LocalDateTime;

public record SupportConversationSummaryResponse(
        Long id,
        Long userId,
        String username,
        String title,
        int messageCount,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

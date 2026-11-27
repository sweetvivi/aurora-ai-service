package com.hub.backend.modules.support.dto;

import java.time.LocalDateTime;

public record SupportHandoffAdminItem(
        Long id,
        Long userId,
        String username,
        String content,
        String contact,
        String pageUrl,
        Long agentConversationId,
        String transcriptSnapshot,
        String status,
        LocalDateTime createdAt,
        LocalDateTime handledAt
) {
}

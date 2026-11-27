package com.hub.backend.modules.support.dto;

import java.time.LocalDateTime;

public record SupportConversationMessageResponse(
        Long id,
        String role,
        String content,
        String status,
        int sequenceNo,
        LocalDateTime createdAt
) {
}

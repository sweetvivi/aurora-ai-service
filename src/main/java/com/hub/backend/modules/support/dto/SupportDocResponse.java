package com.hub.backend.modules.support.dto;

import java.time.LocalDateTime;

public record SupportDocResponse(
        Long id,
        String title,
        String fileName,
        String contentType,
        boolean enabled,
        int chunkCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

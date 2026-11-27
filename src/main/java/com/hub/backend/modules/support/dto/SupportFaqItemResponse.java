package com.hub.backend.modules.support.dto;

public record SupportFaqItemResponse(
        Long id,
        String question,
        String answer,
        String keywords,
        boolean enabled,
        int sortOrder
) {
}

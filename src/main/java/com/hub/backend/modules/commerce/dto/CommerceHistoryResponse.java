package com.hub.backend.modules.commerce.dto;

import com.hub.backend.modules.commerce.entity.CommerceGenerationHistoryEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "CommerceHistoryItem", description = "电商工具生成历史")
public record CommerceHistoryResponse(
        Long id,
        String toolKey,
        String toolLabel,
        String prompt,
        String url,
        LocalDateTime createdAt
) {
    public static CommerceHistoryResponse from(CommerceGenerationHistoryEntity entity) {
        return new CommerceHistoryResponse(
                entity.getId(), entity.getToolKey(), entity.getToolLabel(), entity.getPrompt(),
                entity.getResultUrl(), entity.getCreatedAt());
    }
}

package com.hub.backend.modules.agent.dto;

import com.hub.backend.modules.agent.entity.AgentCategoryEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AgentCategory", description = "智能体分类")
public record AgentCategoryResponse(
        Long id,
        String categoryKey,
        String name,
        String iconUrl,
        String description,
        int sortOrder
) {

    public static AgentCategoryResponse from(AgentCategoryEntity category) {
        return new AgentCategoryResponse(
                category.getId(),
                category.getCategoryKey(),
                category.getName(),
                category.getIconUrl(),
                category.getDescription(),
                category.getSortOrder());
    }
}

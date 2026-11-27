package com.hub.backend.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AgentFavorite", description = "智能体收藏状态")
public record AgentFavoriteResponse(
        Long agentId,
        boolean favorite,
        int favoriteCount
) {
}

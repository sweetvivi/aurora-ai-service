package com.hub.backend.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

@Schema(name = "Agent", description = "智能体详情")
public record AgentResponse(
        Long id,
        Long siteId,
        Long categoryId,
        String categoryName,
        Long ownerUserId,
        String agentKey,
        String sn,
        String agentType,
        Integer uiType,
        Integer scope,
        String classify,
        String childClassify,
        /**
         * 引用的电商工具 key；非空表示这个智能体是「表单形态」，详情页会渲染工具面板。
         */
        String linkedToolKey,
        Map<String, Object> uiConfig,
        boolean chatSuggest,
        String uiBg,
        String name,
        String description,
        String iconUrl,
        String systemPrompt,
        String chatPrologue,
        String sourceModel,
        String sourcePath,
        String visibility,
        String publishStatus,
        String status,
        BigDecimal score,
        int seeCount,
        int useCount,
        int favoriteCount,
        boolean hot,
        boolean favorite,
        List<String> tags,
        List<Long> modelIds,
        Long defaultModelId,
        /**
         * 同上两个字段的可读版本，顺序与 {@code modelIds} 一致；模型被停用或删除时会少几项。
         */
        List<AgentModelOptionResponse> models,
        List<String> abilityKeys,
        List<String> questions,
        LocalDateTime publishedAt,
        int sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

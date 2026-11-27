package com.hub.backend.modules.support.dto;

import java.util.List;

public record SupportBotConfigResponse(
        Long id,
        Long siteId,
        String agentSn,
        Long agentId,
        boolean enabled,
        String onlineMode,
        String timezone,
        String scheduleJson,
        String welcomeTitle,
        String welcomeDescription,
        String homeTitle,
        String brandName,
        String brandColor,
        /** 客服上游基地址；可明文回显 */
        String platformBaseUrl,
        /** 是否已配置平台 API Key；密钥原文永不回显 */
        boolean platformApiKeySet,
        String systemPrompt,
        String chatPrologue,
        String agentName,
        String agentIconUrl,
        List<Long> modelIds,
        Long defaultModelId
) {
}

package com.hub.backend.modules.support.dto;

import java.util.List;

public record SupportWidgetConfigResponse(
        boolean enabled,
        boolean online,
        String onlineMode,
        String homeTitle,
        String welcomeTitle,
        String welcomeDescription,
        String brandName,
        String brandColor,
        String agentSn,
        String chatPrologue,
        String agentName,
        String agentIconUrl,
        List<SupportFaqItemResponse> faqs
) {
}

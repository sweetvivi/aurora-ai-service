package com.hub.backend.modules.support.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record SupportBotConfigUpdateRequest(
        Boolean enabled,
        @Size(max = 32) String onlineMode,
        @Size(max = 64) String timezone,
        String scheduleJson,
        @Size(max = 255) String welcomeTitle,
        @Size(max = 1024) String welcomeDescription,
        @Size(max = 255) String homeTitle,
        @Size(max = 128) String brandName,
        @Size(max = 32) String brandColor,
        /**
         * 上游基地址。{@code null} 表示不改；空字符串表示清除；非空表示覆盖。
         * 例：{@code https://api.example.com/v1}
         */
        @Size(max = 512) String platformBaseUrl,
        /**
         * 平台 API Key。{@code null} 表示不改；空字符串表示清除；非空表示覆盖写入。
         * 响应永不回显原文。
         */
        @Size(max = 2048) String platformApiKey,
        @Size(max = 255) String agentName,
        @Size(max = 1024) String agentIconUrl,
        String systemPrompt,
        String chatPrologue,
        @Size(max = 1) List<Long> modelIds,
        Long defaultModelId
) {
}

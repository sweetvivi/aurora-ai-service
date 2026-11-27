package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "SiteBrandingHistoryItem", description = "站点 Logo 一次变更记录")
public record SiteBrandingHistoryItem(
        @Schema(description = "记录 id") Long id,
        @Schema(description = "槽位 key", example = "auth") String slot,
        @Schema(description = "变更前地址；清除前为空配置时为 null") String oldUrl,
        @Schema(description = "变更后地址；清除后为 null") String newUrl,
        @Schema(description = "变更时间") LocalDateTime createdAt
) {
}

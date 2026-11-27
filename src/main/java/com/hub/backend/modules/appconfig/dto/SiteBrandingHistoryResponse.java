package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "SiteBrandingHistory", description = "站点 Logo 变更历史")
public record SiteBrandingHistoryResponse(
        @Schema(description = "按时间倒序的变更列表") List<SiteBrandingHistoryItem> items
) {
}

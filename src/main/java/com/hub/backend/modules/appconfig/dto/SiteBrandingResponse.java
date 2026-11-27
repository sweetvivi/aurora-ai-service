package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SiteBranding", description = "站点 Logo 配置")
public record SiteBrandingResponse(
        @Schema(description = "各位置 Logo 原始配置值")
        SiteBrandingLogos logos
) {
}

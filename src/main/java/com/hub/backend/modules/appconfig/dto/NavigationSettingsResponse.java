package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "NavigationSettingsResponse", description = "前台导航入口显示与标题布局设置")
public record NavigationSettingsResponse(
        @Schema(description = "站点标识", example = "default") String siteKey,
        @Schema(description = "导航 key 到是否显示的映射") Map<String, Boolean> navigation,
        @Schema(description = "按前台显示顺序排列的标题及功能归属") List<NavigationSection> navigationSections
) {
}

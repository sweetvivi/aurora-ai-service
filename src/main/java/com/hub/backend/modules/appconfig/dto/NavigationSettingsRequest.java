package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "NavigationSettingsRequest", description = "前台导航入口显示与标题布局设置")
public record NavigationSettingsRequest(
        @Schema(
                description = "导航 key 到是否显示的映射；支持只提交需要修改的项",
                example = "{\"shop\":false,\"edu\":true}")
        Map<String, Boolean> navigation,
        @Schema(description = "完整的导航标题布局；不传时保持现有布局")
        List<NavigationSection> navigationSections
) {
}

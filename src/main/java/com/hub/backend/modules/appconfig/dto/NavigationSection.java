package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "NavigationSection", description = "前台导航标题及所属功能入口")
public record NavigationSection(
        @Schema(description = "稳定的标题 ID", example = "new-features") String id,
        @Schema(description = "前台显示的标题；空字符串表示不显示标题", example = "新功能") String title,
        @Schema(description = "按显示顺序排列的功能 key") List<String> itemKeys
) {
}

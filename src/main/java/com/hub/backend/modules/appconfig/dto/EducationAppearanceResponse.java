package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EducationAppearance", description = "AI 教育首页展示配置")
public record EducationAppearanceResponse(
        @Schema(description = "首页右侧插图地址，未配置时为 null",
                example = "/media/commerce/3f2a1c.gif")
        String heroImageUrl
) {
}

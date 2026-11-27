package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "EducationAppearanceRequest", description = "AI 教育首页展示配置写入")
public record EducationAppearanceRequest(
        @Size(max = 2048)
        @Schema(description = "首页右侧插图地址；空字符串表示清除")
        String heroImageUrl
) {
}

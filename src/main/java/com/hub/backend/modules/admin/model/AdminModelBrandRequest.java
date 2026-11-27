package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "AdminModelBrandRequest", description = "创建或更新模型厂商")
public record AdminModelBrandRequest(
        @Schema(description = "厂商 key（kebab-case ascii），创建后建议不改", example = "kling")
        @NotBlank(message = "brandKey must not be blank")
        @Size(max = 64)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "brandKey must be lowercase kebab-case ascii")
        String brandKey,

        @Schema(description = "展示名称", example = "可灵")
        @NotBlank(message = "displayName must not be blank")
        @Size(max = 128)
        String displayName,

        @Schema(description = "图标地址")
        @Size(max = 1024)
        String iconUrl,

        @Schema(description = "短说明，前台副标题")
        @Size(max = 512)
        String description,

        @Schema(description = "排序号，越小越靠前")
        @Min(value = 0, message = "sortOrder must not be negative")
        Integer sortOrder,

        @Schema(description = "状态：ACTIVE / DISABLED")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status must be ACTIVE or DISABLED")
        String status
) {
}

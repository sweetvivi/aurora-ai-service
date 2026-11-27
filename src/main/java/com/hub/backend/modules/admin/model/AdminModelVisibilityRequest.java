package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(name = "AdminModelVisibilityRequest", description = "修改模型在前台的显示状态")
public record AdminModelVisibilityRequest(
        @Schema(description = "ACTIVE 显示，DISABLED 隐藏", example = "DISABLED")
        @NotBlank(message = "status must not be blank")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status must be ACTIVE or DISABLED")
        String status
) {
}

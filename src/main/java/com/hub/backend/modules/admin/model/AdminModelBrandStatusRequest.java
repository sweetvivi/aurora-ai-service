package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(name = "AdminModelBrandStatusRequest", description = "启停模型厂商")
public record AdminModelBrandStatusRequest(
        @Schema(description = "状态：ACTIVE / DISABLED", example = "ACTIVE")
        @NotBlank(message = "status must not be blank")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status must be ACTIVE or DISABLED")
        String status
) {
}

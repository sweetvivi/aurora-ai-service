package com.hub.backend.modules.banner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(name = "BannerStatusRequest", description = "只更新 Banner 启停状态")
public record BannerStatusRequest(
        @Schema(description = "ACTIVE / DISABLED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "status must not be blank")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status must be ACTIVE or DISABLED")
        String status
) {
}

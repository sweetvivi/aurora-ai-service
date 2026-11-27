package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/auth/password}.
 */
@Schema(name = "ChangePasswordRequest", description = "修改登录密码请求")
public record ChangePasswordRequest(
        @Schema(description = "当前密码，8-128 个 Unicode 字符",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "currentPassword must not be blank")
        String currentPassword,

        @Schema(description = "新密码，8-128 个 Unicode 字符",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "newPassword must not be blank")
        String newPassword
) {
}

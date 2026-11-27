package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code DELETE /api/v1/auth/account}.
 */
@Schema(name = "DeleteAccountRequest", description = "注销账号请求")
public record DeleteAccountRequest(
        @Schema(description = "当前登录密码，8-128 个 Unicode 字符",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "password must not be blank")
        String password,

        @Schema(description = "固定确认文字：注销账号", example = "注销账号",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "confirmation must not be blank")
        @Size(max = 32, message = "confirmation must be at most 32 characters")
        String confirmation
) {
}

package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}.
 */
@Schema(name = "LoginRequest", description = "登录请求")
public record LoginRequest(
        @Schema(description = "登录名", requiredMode = Schema.RequiredMode.REQUIRED, example = "alice")
        @NotBlank(message = "username must not be blank")
        @Size(max = 64, message = "username must be at most 64 characters")
        String username,

        @Schema(description = "密码，8-128 个 Unicode 字符", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "correct-horse-battery")
        @NotBlank(message = "password must not be blank")
        String password,

        @Schema(description = "客户端类型，用于会话列表区分来源", example = "web")
        @Size(max = 32, message = "clientType must be at most 32 characters")
        String clientType
) {
}

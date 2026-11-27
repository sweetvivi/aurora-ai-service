package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/refresh}.
 */
@Schema(name = "RefreshTokenRequest", description = "刷新令牌请求")
public record RefreshTokenRequest(
        @Schema(description = "登录时返回的 refreshToken", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "refreshToken must not be blank")
        @Size(max = 512, message = "refreshToken must be at most 512 characters")
        String refreshToken
) {
}

package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/session/password}.
 *
 * <p>Used after a 428: the session token is valid but the server no longer holds the plaintext
 * password it needs to act as this user on New API. Confirming once puts it back in memory, after
 * which the original request can simply be retried.</p>
 */
@Schema(name = "ConfirmSessionPasswordRequest", description = "确认当前账号的登录密码")
public record ConfirmSessionPasswordRequest(
        @Schema(description = "账号登录密码，仅用于服务端内存，不落库", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "password must not be blank")
        @Size(max = 128, message = "password length is invalid")
        String password
) {
}

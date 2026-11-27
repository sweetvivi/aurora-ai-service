package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/email-code}.
 *
 * <p>Only the address: the code New API mails back is entered in the registration form, not here.
 * Sending and checking stay two separate calls because the upstream keeps the code in memory and
 * only checks it when the registration is submitted.</p>
 */
@Schema(name = "EmailCodeRequest", description = "发送邮箱验证码")
public record EmailCodeRequest(
        @Schema(description = "接收验证码的邮箱", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "alice@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "email must be at most 128 characters")
        String email
) {
}

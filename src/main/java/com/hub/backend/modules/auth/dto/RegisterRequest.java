package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/register}.
 */
@Schema(name = "RegisterRequest", description = "注册请求")
public record RegisterRequest(
        @Schema(description = "登录名，3-20 位字母数字与 _ . -；同时是 New API 的用户名",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "alice")
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 20, message = "用户名长度需为 3–20 个字符")
        @Pattern(regexp = "[A-Za-z0-9_.-]+",
                message = "用户名仅支持字母、数字与 _ . -")
        String username,

        @Schema(description = "密码，8-128 个 Unicode 字符；服务端以 Argon2id 存储，绝不回传",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "correct-horse-battery")
        @NotBlank(message = "密码不能为空")
        String password,

        @Schema(description = "昵称，缺省取登录名", example = "Alice")
        @Size(max = 128, message = "nickname must be at most 128 characters")
        String nickname,

        /*
         * Required only while New API requires email verification, which is a runtime property of
         * the upstream deployment (see app.auth.email-verification.register-mode). A fixed
         * @NotBlank here would reject registrations on deployments that never ask for the address,
         * so the requirement is enforced in AuthService, next to the message the user sees.
         */
        @Schema(description = "邮箱。上游开启邮箱验证时必填，验证码会寄到这个地址",
                example = "alice@example.com")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "email must be at most 128 characters")
        String email,

        @Schema(description = "邮箱验证码。上游开启邮箱验证时必填，取自邮件里的 6 位数字",
                example = "483920")
        @Size(max = 64, message = "emailCode must be at most 64 characters")
        String emailCode,

        @Schema(description = "客户端类型，用于会话列表区分来源", example = "web")
        @Size(max = 32, message = "clientType must be at most 32 characters")
        String clientType,

        /*
         * Both are optional here on purpose: whether a captcha is required depends on runtime
         * configuration (app.captcha.enabled), so the decision and its user-facing message belong
         * to AuthService rather than to a fixed @NotBlank. Only the length ceiling is unconditional,
         * to keep oversized junk out of the challenge map lookup.
         */
        @Schema(description = "验证码句柄，取自 GET /auth/captcha",
                example = "9f2c4d1e8b7a6c5d4e3f2a1b0c9d8e7f")
        @Size(max = 64, message = "captchaId must be at most 64 characters")
        String captchaId,

        @Schema(description = "用户看图片后填写的验证码，不区分大小写", example = "K7XQ")
        @Size(max = 16, message = "captchaCode must be at most 16 characters")
        String captchaCode
) {
}

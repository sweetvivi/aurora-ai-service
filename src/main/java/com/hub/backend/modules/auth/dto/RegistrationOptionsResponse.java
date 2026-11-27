package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the registration form is allowed to render, answered before the user types anything.
 *
 * <p>Whether an address is required belongs to the upstream deployment (New API's
 * {@code /api/status}), so a browser cannot derive it. Without this answer the client only has two
 * bad options: always show an email box that the deployment cannot use, or show it and find out
 * after a failed "send code" click.</p>
 *
 * @param emailVerificationRequired registration must carry {@code email} + {@code emailCode}
 * @param emailCodeAvailable        the upstream can actually mail a code, i.e.
 *                                  {@code POST /auth/email-code} will not answer {@code 404}
 * @param captchaRequired           registration must carry the image captcha
 */
@Schema(name = "RegistrationOptionsResponse", description = "注册表单能力")
public record RegistrationOptionsResponse(
        @Schema(description = "注册是否必须带邮箱与邮箱验证码", example = "true")
        boolean emailVerificationRequired,
        @Schema(description = "上游是否能发送邮箱验证码（未启用邮箱验证时为 false）", example = "true")
        boolean emailCodeAvailable,
        @Schema(description = "注册是否需要图形验证码", example = "true")
        boolean captchaRequired
) {
}

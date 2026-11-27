package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code GET /api/v1/auth/captcha}.
 *
 * <p>The answer is deliberately absent: the client only ever holds the handle and the picture.</p>
 */
@Schema(name = "CaptchaChallengeResponse", description = "注册验证码")
public record CaptchaChallengeResponse(
        @Schema(description = "验证码句柄，提交注册时原样回传", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "9f2c4d1e8b7a6c5d4e3f2a1b0c9d8e7f")
        String captchaId,

        @Schema(description = "PNG 图片，已编码为 data URL，可直接作为 img 的 src",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "data:image/png;base64,iVBORw0KGgo...")
        String image,

        @Schema(description = "有效期（秒）", example = "300")
        long expiresInSeconds
) {
}

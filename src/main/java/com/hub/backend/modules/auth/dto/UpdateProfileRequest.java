package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/auth/profile}.
 *
 * <p>Blank contact values explicitly unbind that channel. Email addresses are normalised to
 * lower case before the site-wide uniqueness check.</p>
 */
@Schema(name = "UpdateProfileRequest", description = "更新当前账号资料")
public record UpdateProfileRequest(
        @Schema(description = "昵称，不能为空", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "Alice")
        @NotBlank(message = "昵称不能为空")
        @Size(max = 128, message = "昵称不能超过 128 个字符")
        String nickname,

        @Schema(description = "绑定邮箱；留空表示解除绑定", example = "alice@example.com")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱不能超过 255 个字符")
        String email,

        @Schema(description = "绑定手机号，11 位中国大陆手机号；留空表示解除绑定",
                example = "13800138000")
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "请输入 11 位中国大陆手机号")
        String phone
) {
}

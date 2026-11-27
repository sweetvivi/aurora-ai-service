package com.hub.backend.modules.auth.dto;

import com.hub.backend.modules.user.entity.UserAccountEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * The authenticated account, as returned by {@code GET /api/v1/auth/me}.
 *
 * <p>Never contains the password hash.</p>
 */
@Schema(name = "AuthUser", description = "当前账号")
public record AuthUserResponse(
        @Schema(description = "账号 ID", example = "1") Long id,
        @Schema(description = "所属站点 ID", example = "1") Long siteId,
        @Schema(description = "登录名", example = "alice") String username,
        @Schema(description = "昵称", example = "Alice") String nickname,
        @Schema(description = "邮箱，未绑定为 null") String email,
        @Schema(description = "邮箱是否已通过上游验证；个人中心手填的邮箱为 false") boolean emailVerified,
        @Schema(description = "手机号，未绑定为 null") String phone,
        @Schema(description = "头像地址，未设置为 null") String avatarUrl,
        @Schema(description = "账号状态", example = "ACTIVE") String status,
        @Schema(description = "会员到期时间，非会员为 null") LocalDateTime vipExpiresAt,
        @Schema(description = "注册时间") LocalDateTime createdAt
) {

    public static AuthUserResponse from(UserAccountEntity account) {
        return new AuthUserResponse(
                account.getId(),
                account.getSiteId(),
                account.getUsername(),
                account.getNickname(),
                account.getEmail(),
                account.isEmailVerified(),
                account.getPhone(),
                account.getAvatarUrl(),
                account.getStatus(),
                account.getVipExpiresAt(),
                account.getCreatedAt());
    }
}

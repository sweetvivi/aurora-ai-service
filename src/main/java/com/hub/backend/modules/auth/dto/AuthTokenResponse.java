package com.hub.backend.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of register / login / refresh.
 *
 * @param tokenType        always {@code Bearer}; put the access token in the Authorization header
 * @param accessToken      short lived signed token
 * @param expiresIn        access token lifetime in seconds
 * @param refreshToken     opaque secret; store it safely and never log it
 * @param refreshExpiresIn refresh token lifetime in seconds
 * @param user             the account the tokens belong to
 */
@Schema(name = "AuthToken", description = "登录令牌")
public record AuthTokenResponse(
        @Schema(description = "令牌类型，固定 Bearer", example = "Bearer") String tokenType,
        @Schema(description = "访问令牌，放进 Authorization: Bearer <token>") String accessToken,
        @Schema(description = "访问令牌有效期（秒）", example = "1800") long expiresIn,
        @Schema(description = "刷新令牌，用于换取新的令牌对") String refreshToken,
        @Schema(description = "刷新令牌有效期（秒）", example = "2592000") long refreshExpiresIn,
        @Schema(description = "当前账号") AuthUserResponse user
) {

    public static AuthTokenResponse of(String accessToken, long expiresIn, String refreshToken,
                                       long refreshExpiresIn, AuthUserResponse user) {
        return new AuthTokenResponse("Bearer", accessToken, expiresIn, refreshToken, refreshExpiresIn, user);
    }
}

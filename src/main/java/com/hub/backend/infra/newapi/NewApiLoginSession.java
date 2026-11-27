package com.hub.backend.infra.newapi;

/**
 * One successful sign-in.
 *
 * @param userId               New API user id
 * @param username             the name New API echoed back (it is case sensitive)
 * @param accessToken          bearer token for the management endpoints
 * @param expiresAtEpochSecond {@code access_expires_at} from the response, {@code 0} when the
 *                             deployment does not send it. 用来在过期前主动续期，而不是等下一个
 *                             请求被 401 拒掉：每被拒一次就得重新登录，而站点的登录会话数是
 *                             有上限的（AUTH_SESSION_LIMIT），攒满之后谁都登不进来。
 */
public record NewApiLoginSession(long userId, String username, String accessToken,
                                long expiresAtEpochSecond) {

    /** 兼容只关心身份与令牌的调用方（注册、绑定校验等）。 */
    public NewApiLoginSession(long userId, String username, String accessToken) {
        this(userId, username, accessToken, 0L);
    }
}

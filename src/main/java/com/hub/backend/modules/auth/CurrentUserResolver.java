package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.config.properties.SecurityProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.logging.LogContext;
import com.hub.backend.modules.appconfig.AppSiteService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the account a request belongs to.
 *
 * <p>HTTP requests resolve accounts only from a valid
 * {@code Authorization: Bearer <access token>}. The development header and configured demo
 * account remain available through the legacy {@link #require(String)} overload for non-request
 * compatibility paths; request-facing account APIs must not use them.</p>
 *
 * <p>A header naming an unknown account, or one belonging to another site, is rejected rather than
 * silently downgraded to the demo account.</p>
 */
@Service
@Transactional
public class CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final AuthService authService;
    private final AppSiteService appSiteService;
    private final UserService userService;
    private final AppProperties appProperties;
    private final SecurityProperties securityProperties;

    public CurrentUserResolver(TokenService tokenService,
                               AuthService authService,
                               AppSiteService appSiteService,
                               UserService userService,
                               AppProperties appProperties,
                               SecurityProperties securityProperties) {
        this.tokenService = tokenService;
        this.authService = authService;
        this.appSiteService = appSiteService;
        this.userService = userService;
        this.appProperties = appProperties;
        this.securityProperties = securityProperties;

        if (securityProperties.isTrustUserHeader()) {
            log.warn("app.security.trust-user-header is enabled: the '{}' header may select the "
                            + "account when no access token is presented. This is a pre-authentication "
                            + "development default and must be disabled outside local development.",
                    securityProperties.getUserHeader());
        }
    }

    /**
     * Strict resolution retained under the historical method name.
     *
     * @deprecated account-specific endpoints must not fall back to a development identity; call
     *             {@link #requireAuthenticated(HttpServletRequest)} directly.
     */
    @Deprecated(forRemoval = false)
    public CurrentUser require(HttpServletRequest request) {
        return requireAuthenticated(request);
    }

    /**
     * @throws UnauthorizedException when no valid access token was presented
     */
    public CurrentUser requireAuthenticated(HttpServletRequest request) {
        CurrentUser user = authService.resolveCurrentUser(requireClaims(request));
        // 请求日志的「谁在操作」就靠这一行：认证不在过滤器里发生，所有接口都从这里取身份，
        // 因此只需在此写入一次，全站接口的日志都会带上 userId / siteId。
        LogContext.setUser(user.userId(), user.siteId());
        return user;
    }

    /**
     * Resolves a caller when a valid access token is present, while keeping public catalogue
     * requests anonymous when no token was supplied. A malformed or expired token still fails
     * closed through the same parser used by authenticated endpoints.
     */
    public CurrentUser resolveOptional(HttpServletRequest request) {
        TokenService.AccessTokenClaims claims = accessTokenClaims(request);
        if (claims == null) {
            return null;
        }
        CurrentUser user = authService.resolveCurrentUser(claims);
        // 匿名可访问的接口（模型目录、分享页）里，登录用户同样要能追溯到人。
        LogContext.setUser(user.userId(), user.siteId());
        return user;
    }

    /**
     * Verifies the access token and its session.
     *
     * @return the verified claims, for endpoints that need the session id (sign out)
     * @throws UnauthorizedException when no valid access token was presented
     */
    public TokenService.AccessTokenClaims requireClaims(HttpServletRequest request) {
        TokenService.AccessTokenClaims claims = accessTokenClaims(request);
        if (claims == null) {
            throw new UnauthorizedException(ApiMessages.LOGIN_REQUIRED);
        }
        CurrentUser user = authService.resolveCurrentUser(claims);
        /*
         * 和 requireAuthenticated 一样必须写一次身份。注销、改密码、传头像这几个接口只取 claims
         * 不取 CurrentUser，漏掉这一行会有两个可观测的后果：访问日志的 user_id 恒为空，
         * 审计则因为 MDC 里没有 uid 而把「用户自己注销」记成「admin-key 操作」。
         */
        LogContext.setUser(user.userId(), user.siteId());
        return claims;
    }

    /**
     * Development fallback: resolves an account from a plain header, or falls back to the demo
     * account.
     *
     * @param rawUserId value of the user header, may be {@code null}
     */
    public CurrentUser require(String rawUserId) {
        Long siteId = appSiteService.requireSiteId(appProperties.getDefaultKey());

        if (securityProperties.isTrustUserHeader() && rawUserId != null && !rawUserId.isBlank()) {
            return CurrentUser.of(requireAccount(siteId, rawUserId.strip()));
        }

        return CurrentUser.of(userService.resolveDefaultUser(siteId, securityProperties.getDefaultUsername()));
    }

    private TokenService.AccessTokenClaims accessTokenClaims(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        if (token.isEmpty()) {
            return null;
        }
        // A malformed, mis-signed or expired token is rejected: an attempt is not a fallback.
        return tokenService.parseAccessToken(token);
    }

    private UserAccountEntity requireAccount(Long siteId, String rawUserId) {
        long userId;
        try {
            userId = Long.parseLong(rawUserId);
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException(
                    "%s must be a numeric account id".formatted(securityProperties.getUserHeader()));
        }

        UserAccountEntity account = userService.requireById(userId);
        if (!siteId.equals(account.getSiteId())) {
            throw new NotFoundException("User %d was not found".formatted(userId));
        }
        return account;
    }
}

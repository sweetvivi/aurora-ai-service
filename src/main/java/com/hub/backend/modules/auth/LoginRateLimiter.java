package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.ratelimit.RateLimiter;
import com.hub.backend.common.ratelimit.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流。
 *
 * <p>与 {@link RegistrationRateLimiter}、{@link EmailCodeRateLimiter} 同一套写法：进程内固定窗口，
 * 没有 Redis。区别在于**只数失败**，而且先探再记 —— 登录成功一次都不该扣额度。</p>
 *
 * <p>两把账，各有各的必要性：</p>
 *
 * <ul>
 *   <li>按来源 IP：挡的是同一出口的批量撞库。只按登录名的话，攻击者可以拿一大批用户名轮流试；</li>
 *   <li>按登录名：挡的是分布式来源对同一账号的慢速爆破。只按 IP 的话，换代理就能继续撞。</li>
 * </ul>
 *
 * <p>不泄漏账号是否存在：计数的触发条件是「凭据错误」这条 401，账号不存在走的是同一条路径，
 * 提示语也只有一句 {@link ApiMessages#LOGIN_RATE_LIMITED}，与具体账号无关。</p>
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    static final String IP_PREFIX = "login:ip:";
    static final String USERNAME_PREFIX = "login:username:";

    private final RateLimiter limiter;
    private final RateLimitProperties properties;

    public LoginRateLimiter(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * 前置检查：额度已经耗尽就直接拒绝，连账号查询和 BCrypt 比对都不跑。
     *
     * @throws TooManyRequestsException 该来源或该登录名的失败次数已超限
     */
    public void requireAllowed(String clientIp, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        RateLimitProperties.Login limits = properties.getLogin();
        String addressKey = key(IP_PREFIX, clientIp);
        String nameKey = key(USERNAME_PREFIX, username);

        RateLimitDecision byIp = limiter.peek(
                addressKey, limits.getMaxFailuresPerIp(), limits.getWindow());
        if (!byIp.allowed()) {
            log.warn("Login rate limit hit for {}; retry in {}s",
                    addressKey, byIp.retryAfterSeconds());
            throw new TooManyRequestsException(
                    ApiMessages.LOGIN_RATE_LIMITED, byIp.retryAfterSeconds());
        }

        RateLimitDecision byName = limiter.peek(
                nameKey, limits.getMaxFailuresPerUsername(), limits.getWindow());
        if (!byName.allowed()) {
            log.warn("Login rate limit hit for {}; retry in {}s",
                    nameKey, byName.retryAfterSeconds());
            throw new TooManyRequestsException(
                    ApiMessages.LOGIN_RATE_LIMITED, byName.retryAfterSeconds());
        }
    }

    /**
     * 记一次凭据错误。只在本就失败的路径上调用，成功登录不受影响。
     */
    public void recordFailure(String clientIp, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        RateLimitProperties.Login limits = properties.getLogin();
        limiter.tryAcquire(key(IP_PREFIX, clientIp),
                limits.getMaxFailuresPerIp(), limits.getWindow());
        limiter.tryAcquire(key(USERNAME_PREFIX, username),
                limits.getMaxFailuresPerUsername(), limits.getWindow());
    }

    /**
     * 成功登录：把该登录名的失败计数清零（打错两次再登进去不该继续被锁）。
     *
     * <p>IP 的计数不跟着清：一次成功登录不该替同一出口上的其它撞库尝试洗账。</p>
     */
    public void recordSuccess(String username) {
        if (!properties.isEnabled()) {
            return;
        }
        limiter.reset(key(USERNAME_PREFIX, username));
    }

    private static String key(String prefix, String value) {
        return prefix + (value == null || value.isBlank() ? "unknown" : value.strip());
    }
}

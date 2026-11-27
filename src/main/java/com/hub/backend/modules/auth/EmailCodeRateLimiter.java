package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.ratelimit.RateLimiter;
import com.hub.backend.common.ratelimit.RateLimitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Limits how often a verification code can be requested.
 *
 * <p>The endpoint costs the site nothing but costs the recipient an inbox entry and the upstream
 * SMTP account a quota, so it is the one unauthenticated path where the abuse target is somebody
 * else's mailbox. Both the caller's address and the mailbox itself are counted.</p>
 */
@Component
public class EmailCodeRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeRateLimiter.class);

    static final String IP_PREFIX = "email-code:ip:";
    static final String EMAIL_PREFIX = "email-code:email:";

    private final RateLimiter limiter;
    private final RateLimitProperties properties;

    public EmailCodeRateLimiter(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * Records one request and rejects it when either budget is spent.
     *
     * @throws TooManyRequestsException when this address or this mailbox asked too often
     */
    public void checkAndRecord(String clientIp, String email) {
        if (!properties.isEnabled()) {
            return;
        }

        RateLimitProperties.EmailCode limits = properties.getEmailCode();
        String addressKey = IP_PREFIX
                + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        String mailboxKey = EMAIL_PREFIX
                + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));

        RateLimitDecision byIp = limiter.tryAcquire(
                addressKey, limits.getMaxPerIp(), limits.getWindow());
        if (!byIp.allowed()) {
            log.warn("Email code rate limit hit for {}; retry in {}s",
                    addressKey, byIp.retryAfterSeconds());
            throw new TooManyRequestsException(
                    ApiMessages.CODE_RATE_LIMITED, byIp.retryAfterSeconds());
        }

        RateLimitDecision byMailbox = limiter.tryAcquire(
                mailboxKey, limits.getMaxPerEmail(), limits.getWindow());
        if (!byMailbox.allowed()) {
            log.warn("Email code rate limit hit for {}; retry in {}s",
                    mailboxKey, byMailbox.retryAfterSeconds());
            throw new TooManyRequestsException(
                    ApiMessages.CODE_MAILBOX_RATE_LIMITED, byMailbox.retryAfterSeconds());
        }
    }
}

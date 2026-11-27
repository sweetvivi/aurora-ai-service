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
 * Per address limit on account creation.
 *
 * <p>Registration is worth guarding first: it is the only unauthenticated endpoint that writes rows
 * and spends real CPU on BCrypt hashing, so it is both the cheapest thing to abuse and the most
 * expensive abuse to absorb.</p>
 *
 * <p>Attempts are counted, not successes. Counting only successful sign ups would let a script keep
 * probing with taken or invalid names for free.</p>
 */
@Component
public class RegistrationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RegistrationRateLimiter.class);

    static final String KEY_PREFIX = "register:";

    private final RateLimiter limiter;
    private final RateLimitProperties rateLimitProperties;

    public RegistrationRateLimiter(RateLimiter limiter, RateLimitProperties rateLimitProperties) {
        this.limiter = limiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * Records one attempt from {@code clientIp} and rejects it when the window is exhausted.
     *
     * @throws TooManyRequestsException when the address exceeded its quota
     */
    public void checkAndRecord(String clientIp) {
        if (!rateLimitProperties.isEnabled()) {
            return;
        }

        RateLimitProperties.Registration registration = rateLimitProperties.getRegistration();
        String key = KEY_PREFIX + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);

        RateLimitDecision decision = limiter.tryAcquire(
                key, registration.getMaxAttemptsPerIp(), registration.getWindow());

        if (!decision.allowed()) {
            log.warn("Registration rate limit hit for {}; retry in {}s", key, decision.retryAfterSeconds());
            throw new TooManyRequestsException(
                    ApiMessages.REGISTRATION_RATE_LIMITED,
                    decision.retryAfterSeconds());
        }
    }
}

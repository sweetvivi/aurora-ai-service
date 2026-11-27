package com.hub.backend.modules.image;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.ratelimit.InMemoryRateLimiter;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The image quota is the only thing standing between one logged in account and an unlimited
 * upstream bill, so the two properties that make it real are pinned here: it counts pictures
 * (not requests) and it accounts per account.
 */
class ImageGenerationRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T08:00:00Z"));
    private final RateLimitProperties properties = new RateLimitProperties();
    private final ImageGenerationRateLimiter limiter = new ImageGenerationRateLimiter(
            new InMemoryRateLimiter(clock, properties), properties);

    private static final CurrentUser ALICE = new CurrentUser(1L, 1L, "alice");
    private static final CurrentUser BOB = new CurrentUser(2L, 1L, "bob");

    @Test
    void countsPicturesInsteadOfRequests() {
        properties.getImage().setMaxImagesPerUser(4);

        // One request for four pictures spends the whole window; a second single picture must not fit.
        assertDoesNotThrow(() -> limiter.checkAndRecord(ALICE, 4));
        assertThrows(TooManyRequestsException.class, () -> limiter.checkAndRecord(ALICE, 1));
    }

    @Test
    void keepsSeparateQuotasPerAccount() {
        properties.getImage().setMaxImagesPerUser(1);

        assertDoesNotThrow(() -> limiter.checkAndRecord(ALICE, 1));
        assertThrows(TooManyRequestsException.class, () -> limiter.checkAndRecord(ALICE, 1));
        assertDoesNotThrow(() -> limiter.checkAndRecord(BOB, 1));
    }

    @Test
    void tellsTheClientHowLongToWait() {
        properties.getImage().setMaxImagesPerUser(1);
        properties.getImage().setWindow(Duration.ofMinutes(1));

        limiter.checkAndRecord(ALICE, 1);
        clock.advance(Duration.ofSeconds(20));

        TooManyRequestsException rejected =
                assertThrows(TooManyRequestsException.class, () -> limiter.checkAndRecord(ALICE, 1));
        assertEquals(40, rejected.getRetryAfterSeconds());
        assertEquals("出图太频繁了，请 40 秒后再试", rejected.getMessage());
    }

    @Test
    void freesQuotaWhenTheWindowRollsOver() {
        properties.getImage().setMaxImagesPerUser(1);
        properties.getImage().setWindow(Duration.ofMinutes(1));

        limiter.checkAndRecord(ALICE, 1);
        clock.advance(Duration.ofMinutes(1));

        assertDoesNotThrow(() -> limiter.checkAndRecord(ALICE, 1));
    }

    @Test
    void doesNothingWhenLimitsAreDisabled() {
        properties.setEnabled(false);
        properties.getImage().setMaxImagesPerUser(1);

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.checkAndRecord(ALICE, 4));
        }
    }

    @Test
    void treatsATooLargeBatchAsRejectedWithoutEatingTheRestOfTheQuota() {
        // count is validated upstream, but a bad value must not turn into "quota silently consumed".
        properties.getImage().setMaxImagesPerUser(4);

        assertThrows(TooManyRequestsException.class, () -> limiter.checkAndRecord(ALICE, 5));
        assertDoesNotThrow(() -> limiter.checkAndRecord(ALICE, 4));
    }
}

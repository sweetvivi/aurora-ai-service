package com.hub.backend.common.ratelimit;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRateLimiterTest {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));

    @Test
    void allowsUpToTheLimitThenRejects() {
        InMemoryRateLimiter limiter = limiter(100);

        assertTrue(limiter.tryAcquire("a", 3, WINDOW).allowed());
        assertTrue(limiter.tryAcquire("a", 3, WINDOW).allowed());
        RateLimitDecision third = limiter.tryAcquire("a", 3, WINDOW);
        assertTrue(third.allowed());
        assertEquals(0, third.remaining());

        RateLimitDecision fourth = limiter.tryAcquire("a", 3, WINDOW);
        assertFalse(fourth.allowed());
        assertTrue(fourth.retryAfterSeconds() > 0, "a rejected request must advertise a retry delay");
    }

    @Test
    void countsKeysIndependently() {
        InMemoryRateLimiter limiter = limiter(100);

        limiter.tryAcquire("a", 1, WINDOW);
        limiter.tryAcquire("a", 1, WINDOW);

        assertTrue(limiter.tryAcquire("b", 1, WINDOW).allowed());
    }

    @Test
    void freesQuotaWhenTheWindowRollsOver() {
        InMemoryRateLimiter limiter = limiter(100);

        limiter.tryAcquire("a", 1, WINDOW);
        assertFalse(limiter.tryAcquire("a", 1, WINDOW).allowed());

        clock.advance(WINDOW);

        assertTrue(limiter.tryAcquire("a", 1, WINDOW).allowed());
    }

    @Test
    void reportsTheRemainingTimeOfTheCurrentWindow() {
        InMemoryRateLimiter limiter = limiter(100);

        limiter.tryAcquire("a", 1, WINDOW);
        clock.advance(Duration.ofMinutes(15));
        RateLimitDecision rejected = limiter.tryAcquire("a", 1, WINDOW);

        assertFalse(rejected.allowed());
        assertEquals(Duration.ofMinutes(45).toSeconds(), rejected.retryAfterSeconds());
    }

    @Test
    void keepsMemoryBoundedWhenManyKeysArrive() {
        InMemoryRateLimiter limiter = limiter(2);

        limiter.tryAcquire("a", 5, WINDOW);
        clock.advance(WINDOW);
        limiter.tryAcquire("b", 5, WINDOW);
        limiter.tryAcquire("c", 5, WINDOW);

        assertTrue(limiter.trackedKeys() <= 2, "tracked keys were " + limiter.trackedKeys());
    }

    @Test
    void resetDropsTheCounter() {
        InMemoryRateLimiter limiter = limiter(100);

        limiter.tryAcquire("a", 1, WINDOW);
        assertFalse(limiter.tryAcquire("a", 1, WINDOW).allowed());

        limiter.reset("a");

        assertTrue(limiter.tryAcquire("a", 1, WINDOW).allowed());
    }

    @Test
    void chargesAWholeBatchAndRollsItBackWhenItDoesNotFit() {
        InMemoryRateLimiter limiter = limiter(100);

        RateLimitDecision batch = limiter.tryAcquire("a", 4, 5, WINDOW);
        assertTrue(batch.allowed());
        assertEquals(1, batch.remaining());

        // 4 more do not fit in the 1 left, and the refused batch must not consume it either —
        // otherwise a client asking for too many at once would lock itself out by retrying.
        assertFalse(limiter.tryAcquire("a", 4, 5, WINDOW).allowed());
        assertTrue(limiter.tryAcquire("a", 1, 5, WINDOW).allowed());
    }

    @Test
    void rejectsABatchLargerThanTheLimitEvenWhenNothingWasUsed() {
        InMemoryRateLimiter limiter = limiter(100);

        assertFalse(limiter.tryAcquire("a", 6, 5, WINDOW).allowed());
        // Still the full quota afterwards, not a negative counter.
        assertEquals(4, limiter.tryAcquire("a", 1, 5, WINDOW).remaining());
    }

    @Test
    void anchorsTheWindowAtTheFirstAttemptInsteadOfAnEpochBoundary() {
        // A short window is where epoch alignment bites: at :59 and :01 two requests land in
        // different buckets, so "1 per 15s" would quietly allow two within a couple of seconds.
        MutableClock anchored = new MutableClock(Instant.parse("2026-10-14T00:00:14Z"));
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(anchored, new RateLimitProperties());

        assertTrue(limiter.tryAcquire("a", 1, Duration.ofSeconds(15)).allowed());

        anchored.advance(Duration.ofSeconds(2));   // crosses the :15 epoch boundary
        RateLimitDecision rejected = limiter.tryAcquire("a", 1, Duration.ofSeconds(15));
        assertFalse(rejected.allowed(), "crossing an epoch boundary must not restore the quota");
        assertEquals(13, rejected.retryAfterSeconds());

        anchored.advance(Duration.ofSeconds(13));  // 15s after the first attempt
        assertTrue(limiter.tryAcquire("a", 1, Duration.ofSeconds(15)).allowed());
    }

    private InMemoryRateLimiter limiter(int maxTrackedKeys) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setMaxTrackedKeys(maxTrackedKeys);
        return new InMemoryRateLimiter(clock, properties);
    }
}

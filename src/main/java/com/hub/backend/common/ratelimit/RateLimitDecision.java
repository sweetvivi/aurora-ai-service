package com.hub.backend.common.ratelimit;

/**
 * Outcome of a rate limit check.
 *
 * @param allowed           whether the request may proceed
 * @param remaining         attempts left in the current window
 * @param retryAfterSeconds seconds until the window frees up, meaningful when rejected
 */
public record RateLimitDecision(boolean allowed, int remaining, long retryAfterSeconds) {

    public static RateLimitDecision allowed(int remaining) {
        return new RateLimitDecision(true, remaining, 0);
    }

    public static RateLimitDecision rejected(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds);
    }
}

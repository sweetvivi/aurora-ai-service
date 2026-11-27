package com.hub.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test can move forward, so window boundaries and retention periods need no sleeping.
 */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public static MutableClock startingNow() {
        return new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
    }

    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

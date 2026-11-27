package com.hub.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock used by time dependent components.
 *
 * <p>Exposed as a bean so tests can substitute a fixed clock instead of sleeping: the rate limiter
 * needs deterministic time to assert window boundaries.</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}

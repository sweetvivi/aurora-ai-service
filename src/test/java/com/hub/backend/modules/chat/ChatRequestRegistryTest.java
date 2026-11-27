package com.hub.backend.modules.chat;

import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestRegistryTest {

    private static final Long OWNER = 7L;
    private static final Long SOMEONE_ELSE = 8L;

    private final MutableClock clock = MutableClock.startingNow();

    private ChatProperties chatProperties;
    private ChatRequestRegistry registry;

    @BeforeEach
    void setUp() {
        chatProperties = new ChatProperties();
        chatProperties.setRequestRetention(Duration.ofMinutes(2));
        registry = new ChatRequestRegistry(clock, chatProperties);
    }

    @Test
    void generatesUniqueRequestIds() {
        assertNotEquals(registry.newRequestId(), registry.newRequestId());
    }

    @Test
    void cancellingAnInFlightGenerationDeliversTheSignal() {
        ChatRequestRegistry.Entry entry = registry.register(OWNER, "req-1");

        assertEquals(ChatRequestRegistry.CancelOutcome.CANCELLED, registry.cancel(OWNER, "req-1"));
        assertTrue(entry.isCancelled());
    }

    @Test
    void cancellingAgainIsIdempotentRatherThanNotFound() {
        registry.register(OWNER, "req-1");
        registry.cancel(OWNER, "req-1");

        // the stop button may be pressed twice, or pressed just as the answer completes
        assertEquals(ChatRequestRegistry.CancelOutcome.ALREADY_FINISHED, registry.cancel(OWNER, "req-1"));
    }

    @Test
    void aFinishedGenerationStaysCancellableForTheRetentionWindow() {
        registry.register(OWNER, "req-1");
        registry.finish(OWNER, "req-1", ChatRequestRegistry.OUTCOME_COMPLETED);

        assertEquals(ChatRequestRegistry.CancelOutcome.ALREADY_FINISHED, registry.cancel(OWNER, "req-1"));

        clock.advance(Duration.ofMinutes(3));

        assertEquals(ChatRequestRegistry.CancelOutcome.NOT_FOUND, registry.cancel(OWNER, "req-1"));
    }

    @Test
    void requestsAreScopedToTheAccountThatStartedThem() {
        registry.register(OWNER, "req-1");

        // the same id from another account must not reach the first account's generation
        assertEquals(ChatRequestRegistry.CancelOutcome.NOT_FOUND, registry.cancel(SOMEONE_ELSE, "req-1"));
        assertFalse(registry.register(OWNER, "req-1").isCancelled());
    }

    @Test
    void unknownRequestIsNotFound() {
        assertEquals(ChatRequestRegistry.CancelOutcome.NOT_FOUND, registry.cancel(OWNER, "ghost"));
    }

    @Test
    void finishRecordsTheOutcomeOnce() {
        ChatRequestRegistry.Entry entry = registry.register(OWNER, "req-1");
        registry.finish(OWNER, "req-1", ChatRequestRegistry.OUTCOME_CANCELLED);

        assertTrue(entry.isTerminal());
        assertEquals(ChatRequestRegistry.OUTCOME_CANCELLED, entry.outcome());

        // a late second call must not rewrite how the generation ended
        registry.finish(OWNER, "req-1", ChatRequestRegistry.OUTCOME_COMPLETED);
        assertEquals(ChatRequestRegistry.OUTCOME_CANCELLED, entry.outcome());
    }

    @Test
    void evictsFinishedRequestsOnceTheirRetentionPasses() {
        registry.register(OWNER, "req-1");
        registry.finish(OWNER, "req-1", ChatRequestRegistry.OUTCOME_COMPLETED);
        assertEquals(1, registry.trackedRequests());

        clock.advance(Duration.ofMinutes(3));
        registry.register(OWNER, "req-2");

        assertEquals(1, registry.trackedRequests());
    }

    @Test
    void keepsMemoryBoundedEvenWhenNothingHasExpired() {
        chatProperties.setMaxTrackedRequests(2);
        ChatRequestRegistry bounded = new ChatRequestRegistry(clock, chatProperties);

        bounded.register(OWNER, "req-1");
        bounded.finish(OWNER, "req-1", ChatRequestRegistry.OUTCOME_COMPLETED);
        bounded.register(OWNER, "req-2");
        bounded.finish(OWNER, "req-2", ChatRequestRegistry.OUTCOME_COMPLETED);
        bounded.register(OWNER, "req-3");

        assertTrue(bounded.trackedRequests() <= 2, "tracked " + bounded.trackedRequests());
    }
}

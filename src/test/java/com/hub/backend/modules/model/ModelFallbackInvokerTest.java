package com.hub.backend.modules.model;

import com.hub.backend.common.exception.LlmInvocationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelFallbackInvokerTest {

    @Test
    void blockingInvocationSwitchesAfterAnUpstreamFailure() {
        ModelUseResolver.Resolved first = resolved(1L, "primary", "route-a");
        ModelUseResolver.Resolved second = resolved(2L, "backup", "route-b");
        List<Long> attempted = new ArrayList<>();

        ModelFallbackInvoker.Result<String> result = ModelFallbackInvoker.invoke(
                List.of(first, second),
                model -> {
                    if (model == first) {
                        throw new LlmInvocationException("primary unavailable");
                    }
                    return "ok";
                },
                model -> attempted.add(model.id()));

        assertEquals(List.of(1L, 2L), attempted);
        assertEquals(2L, result.model().id());
        assertEquals(1, result.selectedIndex());
        assertEquals("ok", result.value());
    }

    @Test
    void streamSwitchesWhenFailureHappensBeforeTheFirstPublishedFrame() {
        ModelUseResolver.Resolved first = resolved(1L, "primary", "route-a");
        ModelUseResolver.Resolved second = resolved(2L, "backup", "route-b");
        List<Long> attempted = new ArrayList<>();

        ModelFallbackInvoker.Result<String> result = ModelFallbackInvoker.stream(
                List.of(first, second),
                (model, markPublished) -> {
                    if (model == first) {
                        throw new LlmInvocationException("failed before output");
                    }
                    markPublished.run();
                    return "visible";
                },
                model -> attempted.add(model.id()));

        assertEquals(List.of(1L, 2L), attempted);
        assertEquals(2L, result.model().id());
        assertEquals("visible", result.value());
    }

    @Test
    void streamDoesNotReplayAfterTheFirstPublishedFrame() {
        ModelUseResolver.Resolved first = resolved(1L, "primary", "route-a");
        ModelUseResolver.Resolved second = resolved(2L, "backup", "route-b");
        int[] calls = {0};
        LlmInvocationException failure = new LlmInvocationException("failed after output");

        LlmInvocationException thrown = assertThrows(
                LlmInvocationException.class,
                () -> ModelFallbackInvoker.stream(
                        List.of(first, second),
                        (model, markPublished) -> {
                            calls[0] += 1;
                            markPublished.run();
                            throw failure;
                        },
                        ignored -> {
                        }));

        assertSame(failure, thrown);
        assertEquals(1, calls[0]);
    }

    @Test
    void nonUpstreamFailuresAreNotRetried() {
        ModelUseResolver.Resolved first = resolved(1L, "primary", "route-a");
        ModelUseResolver.Resolved second = resolved(2L, "backup", "route-b");
        int[] calls = {0};
        IllegalStateException failure = new IllegalStateException("validation failed");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ModelFallbackInvoker.invoke(
                        List.of(first, second),
                        model -> {
                            calls[0] += 1;
                            throw failure;
                        },
                        ignored -> {
                        }));

        assertSame(failure, thrown);
        assertEquals(1, calls[0]);
    }

    @Test
    void skipsCandidatesWithAnAlreadyAttemptedNormalizedRoute() {
        ModelUseResolver.Resolved first = resolved(1L, "primary", "route-a");
        ModelUseResolver.Resolved duplicate = resolved(2L, "duplicate", " ROUTE-A ");
        ModelUseResolver.Resolved distinct = resolved(3L, "backup", "route-b");
        List<Long> attempted = new ArrayList<>();

        ModelFallbackInvoker.Result<String> result = ModelFallbackInvoker.invoke(
                List.of(first, duplicate, distinct),
                model -> {
                    if (model == first) {
                        throw new LlmInvocationException("primary unavailable");
                    }
                    return "ok";
                },
                model -> attempted.add(model.id()));

        assertEquals(List.of(1L, 3L), attempted);
        assertEquals(3L, result.model().id());
    }

    private static ModelUseResolver.Resolved resolved(Long id, String key, String route) {
        return new ModelUseResolver.Resolved(
                id, key, route, "CHAT", true, false, null, false, null, null);
    }
}

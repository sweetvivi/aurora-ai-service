package com.hub.backend.modules.model;

import com.hub.backend.common.exception.LlmInvocationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Executes an already-resolved fallback chain.
 *
 * <p>Only {@link LlmInvocationException} triggers a model switch. Cancellation, validation and
 * client-delivery failures propagate immediately because retrying them against another model
 * would either be incorrect or could repeat a user-visible call.</p>
 */
public final class ModelFallbackInvoker {

    private ModelFallbackInvoker() {
    }

    public static <T> Result<T> invoke(
            List<ModelUseResolver.Resolved> candidates,
            Function<ModelUseResolver.Resolved, T> invocation,
            Consumer<ModelUseResolver.Resolved> attemptObserver) {
        return invoke(
                candidates,
                (model, guard) -> invocation.apply(model),
                attemptObserver,
                false);
    }

    /**
     * Streaming variant. The invocation receives a guard that must be called immediately before
     * publishing the first meaningful reasoning, content or tool frame. Once guarded, an upstream
     * failure is final and the partial output is not replayed on another model.
     */
    public static <T> Result<T> stream(
            List<ModelUseResolver.Resolved> candidates,
            BiFunction<ModelUseResolver.Resolved, Runnable, T> invocation,
            Consumer<ModelUseResolver.Resolved> attemptObserver) {
        return invoke(
                candidates,
                (model, guard) -> invocation.apply(model, guard::markPublished),
                attemptObserver,
                true);
    }

    private static <T> Result<T> invoke(
            List<ModelUseResolver.Resolved> candidates,
            Attempt<T> invocation,
            Consumer<ModelUseResolver.Resolved> attemptObserver,
            boolean streaming) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one model candidate is required");
        }

        Set<String> routes = new HashSet<>();
        List<ModelUseResolver.Resolved> attempted = new ArrayList<>();
        LlmInvocationException lastFailure = null;

        for (int index = 0; index < candidates.size(); index += 1) {
            ModelUseResolver.Resolved candidate = candidates.get(index);
            if (!routes.add(normalizedRoute(candidate.upstreamKey()))) {
                continue;
            }
            attempted.add(candidate);
            attemptObserver.accept(candidate);

            PublishGuard guard = new PublishGuard();
            try {
                T value = invocation.run(candidate, guard);
                return new Result<>(candidate, attempted, candidates.indexOf(candidate), value);
            } catch (LlmInvocationException failure) {
                if (lastFailure != null) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
                if (streaming && guard.published) {
                    throw failure;
                }
            }
        }

        if (lastFailure == null) {
            throw new LlmInvocationException("No distinct model candidates were available");
        }
        throw lastFailure;
    }

    private static String normalizedRoute(String route) {
        return route == null ? "" : route.strip().toLowerCase(Locale.ROOT);
    }

    public record Result<T>(
            ModelUseResolver.Resolved model,
            List<ModelUseResolver.Resolved> attempted,
            int selectedIndex,
            T value) {

        public Result {
            attempted = List.copyOf(attempted);
        }
    }

    private static final class PublishGuard {

        private boolean published;

        private void markPublished() {
            published = true;
        }
    }

    @FunctionalInterface
    private interface Attempt<T> {

        T run(ModelUseResolver.Resolved model, PublishGuard guard);
    }
}

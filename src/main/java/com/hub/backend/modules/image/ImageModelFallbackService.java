package com.hub.backend.modules.image;

import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.modules.model.ModelFallbackInvoker;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.List;
import java.util.function.Function;

/**
 * Retries an image call with the configured image model when the gateway rejects the requested
 * model because it has not been priced yet.
 *
 * <p>New API answers such requests with HTTP 400 before generating anything. Retrying once with the
 * established default therefore does not bill a second generation. Other upstream errors and
 * aliases that resolve to the same route are returned unchanged.</p>
 */
@Service
public class ImageModelFallbackService {

    private static final Logger log = LoggerFactory.getLogger(ImageModelFallbackService.class);

    private final ModelUseResolver modelResolver;

    public ImageModelFallbackService(ModelUseResolver modelResolver) {
        this.modelResolver = modelResolver;
    }

    public <T> InvocationResult<T> invoke(
            Long siteId,
            ModelUseResolver.Resolved primary,
            Function<ModelUseResolver.Resolved, T> invocation) {
        List<ModelUseResolver.Resolved> candidates = modelResolver.resolveAllForSite(
                siteId, primary.requestKey(), LlmModelEntity.TYPE_IMAGE);
        if (candidates == null || candidates.isEmpty()) {
            candidates = List.of(primary);
        }
        return invoke(siteId, candidates, invocation);
    }

    /**
     * Runs the full group chain and keeps the legacy unpriced-model fallback behind it.
     */
    public <T> InvocationResult<T> invoke(
            Long siteId,
            List<ModelUseResolver.Resolved> candidates,
            Function<ModelUseResolver.Resolved, T> invocation) {
        try {
            ModelFallbackInvoker.Result<T> result = ModelFallbackInvoker.invoke(
                    candidates, invocation, ignored -> {
                    });
            return new InvocationResult<>(
                    result.model(), result.value(), result.selectedIndex() > 0);
        } catch (LlmInvocationException exception) {
            return invokeLegacyDefaultFallback(siteId, candidates, invocation, exception);
        }
    }

    private <T> InvocationResult<T> invokeLegacyDefaultFallback(
            Long siteId,
            List<ModelUseResolver.Resolved> attempted,
            Function<ModelUseResolver.Resolved, T> invocation,
            LlmInvocationException exception) {
        ModelUseResolver.Resolved last = attempted.get(attempted.size() - 1);
        ModelUseResolver.Resolved fallback = resolveFallback(siteId, last, exception);
        if (attempted.stream().anyMatch(candidate -> sameRoute(candidate, fallback))) {
            throw exception;
        }
        log.warn("Image model '{}' is not priced upstream; retrying with '{}'",
                last.upstreamKey(), fallback.upstreamKey());
        try {
            return new InvocationResult<>(fallback, invocation.apply(fallback), true);
        } catch (LlmInvocationException retryFailure) {
            retryFailure.addSuppressed(exception);
            throw retryFailure;
        }
    }

    private ModelUseResolver.Resolved resolveFallback(
            Long siteId,
            ModelUseResolver.Resolved primary,
            LlmInvocationException exception) {
        if (!hasUnpricedModelError(exception)) {
            throw exception;
        }

        // 站点解析链已经在 ModelUseResolver 里做过目录回退，这里跟着它走而不是再读静态配置：
        // 静态键一旦被上游退役，重试会以一个 404 收场，而调用方看到的仍是"未定价"的 400，
        // 两个错误叠在一起更难定位。
        ModelUseResolver.Resolved fallback;
        try {
            fallback = modelResolver.resolveAllForSite(
                    siteId, null, LlmModelEntity.TYPE_IMAGE).get(0);
        } catch (RuntimeException unresolved) {
            throw exception;
        }
        if (sameRoute(primary, fallback)) {
            throw exception;
        }
        return fallback;
    }

    private static boolean hasUnpricedModelError(LlmInvocationException exception) {
        LlmInvocationException current = exception;
        while (current != null) {
            if (isUnpricedModelError(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next instanceof LlmInvocationException llmFailure) {
                current = llmFailure;
                continue;
            }
            current = null;
        }
        for (Throwable suppressed : exception.getSuppressed()) {
            if (suppressed instanceof LlmInvocationException llmFailure
                    && hasUnpricedModelError(llmFailure)) {
                return true;
            }
        }
        return false;
    }

    static boolean isUnpricedModelError(LlmInvocationException exception) {
        if (exception == null || exception.getMessage() == null) {
            return false;
        }
        String message = exception.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("model_price_error")
                || message.contains("has not been priced")
                || message.contains("not been priced");
    }

    private static boolean sameRoute(
            ModelUseResolver.Resolved primary,
            ModelUseResolver.Resolved fallback) {
        String primaryRoute = normalized(primary.upstreamKey());
        String fallbackRoute = normalized(fallback.upstreamKey());
        return !primaryRoute.isEmpty() && primaryRoute.equals(fallbackRoute);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    public record InvocationResult<T>(
            ModelUseResolver.Resolved model,
            T value,
            boolean fallbackUsed) {
    }
}

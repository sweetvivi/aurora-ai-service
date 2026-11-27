package com.hub.backend.common.ratelimit;

import com.hub.backend.common.config.properties.RateLimitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process local fixed window rate limiter — the default when {@code app.redis.enabled} is off.
 *
 * <p>它按实例计数：单实例部署没问题，多副本时每个节点各算一份，实际额度会变成
 * {@code limit × nodes}。需要跨实例一致时把 {@code app.redis.enabled} 打开，
 * 换成 {@code infra.redis.RedisRateLimiter}（同一接口、同一窗口语义）。</p>
 *
 * <p>窗口按每个键的第一次尝试起算，而不是按整点对齐 —— 理由见 {@link RateLimiter}。</p>
 *
 * <p>键空间由 {@code app.rate-limit.max-tracked-keys} 兜底：超过上限后先丢已经关闭的窗口，
 * 还超就继续丢最老的一批。丢计数等于让那批来源重获额度，但无界增长会把整个实例拖垮，
 * 两害相权取前者。</p>
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxTrackedKeys;

    public InMemoryRateLimiter(Clock clock, RateLimitProperties rateLimitProperties) {
        this.clock = clock;
        this.maxTrackedKeys = Math.max(1, rateLimitProperties.getMaxTrackedKeys());
    }

    /**
     * Records one attempt and reports whether it is allowed.
     *
     * @param key         identifies what is being limited, for example {@code register:203.0.113.7}
     * @param maxAttempts attempts accepted inside the window
     * @param window      length of the counting window
     */
    @Override
    public RateLimitDecision tryAcquire(String key, int maxAttempts, Duration window) {
        return tryAcquire(key, 1, maxAttempts, window);
    }

    /**
     * Records {@code cost} units at once and reports whether they fit.
     *
     * <p>Needed because one request is not one unit everywhere: a text to image call may ask for
     * four pictures, and counting that as a single attempt would let a client spend four times the
     * intended quota per window. A rejected batch is rolled back, so repeatedly retrying with too
     * large a batch cannot starve the client out of its remaining quota.</p>
     *
     * @param key         identifies what is being limited
     * @param cost        units consumed by this attempt, at least 1
     * @param maxAttempts units accepted inside the window
     * @param window      length of the counting window
     */
    @Override
    public RateLimitDecision tryAcquire(String key, int cost, int maxAttempts, Duration window) {
        int weight = Math.max(1, cost);
        long windowSeconds = Math.max(1, window.toSeconds());
        long now = clock.instant().getEpochSecond();

        // The window is anchored at the key's first attempt, not at an epoch-aligned boundary.
        // Epoch alignment looks harmless until the window gets short: with a 15s window every key
        // regains its whole quota at :00/:15/:30/:45, so a caller limited to "1 per 15s" can get
        // two through 14 seconds apart. Anchoring per key makes Retry-After true.
        Window current = windows.compute(key, (ignored, existing) ->
                existing == null || now >= existing.bucketStart() + windowSeconds
                        ? new Window(now, new AtomicInteger())
                        : existing);

        int used = current.attempts().addAndGet(weight);
        evictIfOverCapacity(now, windowSeconds);

        if (used > maxAttempts) {
            // The whole batch is refused, so it must not consume quota either.
            current.attempts().addAndGet(-weight);
            return RateLimitDecision.rejected(current.bucketStart() + windowSeconds - now);
        }
        return RateLimitDecision.allowed(maxAttempts - used);
    }

    /**
     * 只读探针：这个键现在还能不能进，不计数。
     *
     * <p>「失败才计数」的限流（登录）需要先判预算再决定要不要动账本，因此不能复用
     * {@link #tryAcquire} —— 它一调用就先记一笔，会把成功登录也算成一次尝试。</p>
     */
    @Override
    public RateLimitDecision peek(String key, int maxAttempts, Duration window) {
        Window current = windows.get(key);
        if (current == null) {
            return RateLimitDecision.allowed(maxAttempts);
        }
        long windowSeconds = Math.max(1, window.toSeconds());
        long now = clock.instant().getEpochSecond();
        if (now >= current.bucketStart() + windowSeconds) {
            return RateLimitDecision.allowed(maxAttempts);
        }
        int used = current.attempts().get();
        if (used >= maxAttempts) {
            return RateLimitDecision.rejected(current.bucketStart() + windowSeconds - now);
        }
        return RateLimitDecision.allowed(maxAttempts - used);
    }

    /** Drops the counter for a key; used by tests and by administrative unblocking. */
    @Override
    public void reset(String key) {
        windows.remove(key);
    }

    /** Drops every counter. Intended for tests and for an operational reset after a false positive. */
    @Override
    public void clear() {
        windows.clear();
    }

    /** @return number of tracked keys, for tests and diagnostics */
    public int trackedKeys() {
        return windows.size();
    }

    private void evictIfOverCapacity(long bucketStart, long windowSeconds) {
        if (windows.size() <= maxTrackedKeys) {
            return;
        }

        // Closed windows first: those counters can never be consulted again.
        long cutoff = bucketStart - windowSeconds;
        windows.entrySet().removeIf(entry -> entry.getValue().bucketStart() < cutoff);
        if (windows.size() <= maxTrackedKeys) {
            return;
        }

        // Still over capacity, so the flood arrived inside one window. Dropping the oldest counters
        // trades exact accounting for bounded memory: the addresses involved simply regain quota.
        // With no Redis and a per-address limit this is the right way round — unbounded growth would
        // take the whole instance down, while a regained quota only weakens a limit that a flood of
        // distinct addresses defeats anyway.
        long oldest = windows.values().stream().mapToLong(Window::bucketStart).min().orElse(bucketStart);
        windows.entrySet().removeIf(entry -> entry.getValue().bucketStart() == oldest);
    }

    private record Window(long bucketStart, AtomicInteger attempts) {
    }
}

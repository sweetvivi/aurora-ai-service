package com.hub.backend.infra.redis;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.ratelimit.RateLimitDecision;
import com.hub.backend.common.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.Cursor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis 版限流账本：N 个副本共用一个窗口计数，重启也不清零。
 *
 * <p>窗口语义与 {@link com.hub.backend.common.ratelimit.InMemoryRateLimiter} 完全一致（按每个键
 * 的第一次尝试起算），差别只在账本放哪。</p>
 *
 * <p><b>时间取 Redis 的</b>：脚本里用 {@code TTL} 推算窗口剩余，而不是拿应用服务器的
 * {@code System.currentTimeMillis()}。多台机器时钟差几十秒很常见，用本机时间会让两个副本对
 * 「窗口结束没有」得出不同结论，限流就形同虚设。</p>
 *
 * <p><b>Redis 故障时放行</b>（fail-open）：限流是防护措施，不是业务正确性。Redis 挂掉时如果
 * 一律拒绝，登录、注册、发图会同时不可用 —— 那比短时间少一层限流严重得多。代价是故障期间
 * 限流失效，所以这里每次都打 warn，便于告警发现。</p>
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * 记一次尝试。返回 {是否放行, 剩余额度, 建议等待秒数}。
     *
     * <p>{@code EXISTS} 与随后的 {@code SET} 在脚本里是原子的，所以两个副本同时打第一次请求
     * 不会各写一个窗口、各自算一次额度。</p>
     */
    private static final String ACQUIRE_SCRIPT = """
            local key = KEYS[1]
            local cost = tonumber(ARGV[1])
            local max = tonumber(ARGV[2])
            local win = tonumber(ARGV[3])
            if redis.call('EXISTS', key) == 0 then
              if cost > max then
                return {0, 0, win}
              end
              redis.call('SET', key, cost, 'EX', win)
              return {1, max - cost, win}
            end
            local used = tonumber(redis.call('GET', key) or '0')
            local ttl = redis.call('TTL', key)
            if ttl < 0 then
              ttl = win
            end
            if used + cost > max then
              return {0, 0, ttl}
            end
            redis.call('INCRBY', key, cost)
            return {1, max - used - cost, ttl}
            """;

    /** 只读探针：不写账本。 */
    private static final String PEEK_SCRIPT = """
            local key = KEYS[1]
            local max = tonumber(ARGV[1])
            if redis.call('EXISTS', key) == 0 then
              return {1, max, 0}
            end
            local used = tonumber(redis.call('GET', key) or '0')
            local ttl = redis.call('TTL', key)
            if ttl < 0 then
              ttl = 0
            end
            if used >= max then
              return {0, 0, ttl}
            end
            return {1, max - used, 0}
            """;

    private final StringRedisTemplate redis;
    private final String prefix;
    private final RedisFailureSwitch failureSwitch;
    private final RedisScript<List> acquireScript;
    private final RedisScript<List> peekScript;

    @Autowired
    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this(redis, properties, new RedisFailureSwitch());
    }

    /** 供测试注入自定义短路窗口。 */
    RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties,
                     RedisFailureSwitch failureSwitch) {
        this.redis = redis;
        this.prefix = properties.getKeyPrefix() == null ? "" : properties.getKeyPrefix();
        this.acquireScript = RedisScript.of(ACQUIRE_SCRIPT, List.class);
        this.peekScript = RedisScript.of(PEEK_SCRIPT, List.class);
        this.failureSwitch = failureSwitch;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int maxAttempts, Duration window) {
        return tryAcquire(key, 1, maxAttempts, window);
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int cost, int maxAttempts, Duration window) {
        int weight = Math.max(1, cost);
        int limit = Math.max(1, maxAttempts);
        long windowSeconds = Math.max(1, window.toSeconds());
        String windowKey = prefix + key;

        return guard(() -> {
            List<?> result = redis.execute(acquireScript, Collections.singletonList(windowKey),
                    String.valueOf(weight), String.valueOf(limit), String.valueOf(windowSeconds));
            return toDecision(result, limit);
        }, RateLimitDecision.allowed(limit), "acquire", windowKey);
    }

    @Override
    public RateLimitDecision peek(String key, int maxAttempts, Duration window) {
        int limit = Math.max(1, maxAttempts);
        String windowKey = prefix + key;

        return guard(() -> {
            List<?> result = redis.execute(peekScript, Collections.singletonList(windowKey),
                    String.valueOf(limit));
            return toDecision(result, limit);
        }, RateLimitDecision.allowed(limit), "peek", windowKey);
    }

    @Override
    public void reset(String key) {
        try {
            redis.delete(prefix + key);
        } catch (RuntimeException exception) {
            log.warn("Redis rate limiter could not reset '{}': {}", prefix + key, exception.getMessage());
        }
    }

    /**
     * 按前缀 SCAN + DEL，而不是 {@code KEYS}：{@code KEYS} 在大库上是 O(n) 阻塞命令，线上调用
     * 会把整个 Redis 卡住。
     */
    @Override
    public void clear() {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(500).build();
            List<String> batch = new ArrayList<>(500);
            try (Cursor<String> cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 500) {
                        redis.delete(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                redis.delete(batch);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis rate limiter could not clear '{}*': {}", prefix, exception.getMessage());
        }
    }

    private static RateLimitDecision toDecision(List<?> result, int limit) {
        if (result == null || result.size() < 3) {
            // 脚本返回形状不对说明脚本被改坏了；放行比误拒更安全，但一定要吼出来。
            log.warn("Unexpected rate-limit script result {}; allowing the request", result);
            return RateLimitDecision.allowed(limit);
        }
        long allowed = number(result.get(0));
        long remaining = number(result.get(1));
        long retryAfter = number(result.get(2));
        return allowed == 1
                ? RateLimitDecision.allowed((int) remaining)
                : RateLimitDecision.rejected(retryAfter);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private <T> T guard(Supplier<T> call, T fallback, String operation, String key) {
        if (failureSwitch.isOpen()) {
            // 刚失败过：直接放行，不再为每个请求付一次超时（见 RedisFailureSwitch）。
            return fallback;
        }
        try {
            T result = call.get();
            failureSwitch.recordSuccess();
            return result;
        } catch (RuntimeException exception) {
            failureSwitch.recordFailure();
            log.warn("Redis rate limiter '{}' failed for '{}' ({}); allowing the request",
                    operation, key, exception.getMessage());
            return fallback;
        }
    }
}

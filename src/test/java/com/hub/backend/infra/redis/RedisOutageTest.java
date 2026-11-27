package com.hub.backend.infra.redis;

import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.ratelimit.RateLimitDecision;
import com.hub.backend.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 运行期故障时的降级：限流**放行**，且不再为每个请求付一次超时。
 *
 * <p>注意区分两种故障：</p>
 * <ul>
 *   <li><b>启动时</b>连不上：监听容器 {@code start()} 会抛异常，应用直接起不来。这是有意的 ——
 *       {@code REDIS_ENABLED=true} 是一份部署契约，配错了应当在部署阶段暴露。</li>
 *   <li><b>运行期</b>连不上（容器重启、网络抖动）：就是本类验证的行为。</li>
 * </ul>
 *
 * <p>用「脚本执行必然抛连接异常」的模板替身，并数它被调用了几次：第一次失败后应当进入短路窗口，
 * 后续调用直接放行、不再撞 Redis。这一条是压测出来的 —— 没短路时 Redis 挂着期间，每个受限请求
 * 都要等满 1 秒超时。</p>
 */
class RedisOutageTest {

    private static final class BrokenTemplate extends StringRedisTemplate {

        private final AtomicInteger scriptCalls = new AtomicInteger();

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            scriptCalls.incrementAndGet();
            throw new RedisConnectionFailureException("Unable to connect to Redis");
        }
    }

    @Test
    void rateLimitsFailOpenAndStopHammeringARedisThatIsDown() {
        BrokenTemplate template = new BrokenTemplate();
        RateLimiter limiter = new RedisRateLimiter(
                template, new RateLimitProperties(), new RedisFailureSwitch(30_000));

        RateLimitDecision first = limiter.tryAcquire("outage:key", 1, Duration.ofMinutes(1));
        assertThat(first.allowed()).isTrue();
        assertThat(template.scriptCalls).as("first call must actually try Redis").hasValue(1);

        // 短路窗口内：仍然放行，但不再调用 Redis。
        assertThat(limiter.tryAcquire("outage:key", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.peek("outage:key", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(template.scriptCalls).as("circuit breaker should skip Redis").hasValue(1);
    }

    @Test
    void theSwitchBelongsToEachLimiterInstance() {
        BrokenTemplate first = new BrokenTemplate();
        BrokenTemplate second = new BrokenTemplate();
        RateLimiter broken = new RedisRateLimiter(first, new RateLimitProperties(), new RedisFailureSwitch(30_000));
        RateLimiter healthy = new RedisRateLimiter(second, new RateLimitProperties(), new RedisFailureSwitch(30_000));

        broken.tryAcquire("outage:key", 1, Duration.ofMinutes(1));
        healthy.tryAcquire("outage:key", 1, Duration.ofMinutes(1));

        // 各自独立：一个进短路不影响另一个（防止有人把状态写成静态的，那会让多实例互相干扰）。
        assertThat(first.scriptCalls).hasValue(1);
        assertThat(second.scriptCalls).hasValue(1);
    }

    @Test
    void resetAndClearDoNotThrowWhenRedisIsDown() {
        RateLimiter limiter = new RedisRateLimiter(
                new BrokenTemplate(), new RateLimitProperties(), new RedisFailureSwitch(30_000));

        limiter.reset("outage:key");
        limiter.clear();
    }
}

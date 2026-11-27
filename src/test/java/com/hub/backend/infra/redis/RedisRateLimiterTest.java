package com.hub.backend.infra.redis;

import com.hub.backend.common.ratelimit.RateLimitDecision;
import com.hub.backend.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 版限流账本的行为。
 *
 * <p>需要一台真的 Redis（{@code 127.0.0.1:6379}）。没有就整体跳过 —— CI 里不装 Redis 也不该
 * 让它变红，但本地/预发应当跑一遍：Lua 脚本的错误（类型、返回值形状）只有真跑才能发现。</p>
 */
@SpringBootTest(properties = "app.redis.enabled=true")
class RedisRateLimiterTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(), "Redis is not running on " + HOST + ":" + PORT + "; skipping");
        limiter.clear();
    }

    @AfterEach
    void tearDown() {
        if (reachable()) {
            limiter.clear();
        }
    }

    @Test
    void countsInRedisSoEveryInstanceSharesOneWindow() {
        assertThat(limiter).isInstanceOf(RedisRateLimiter.class);

        assertThat(limiter.tryAcquire("test:basic", 2, Duration.ofMinutes(1)).allowed()).isTrue();
        RateLimitDecision second = limiter.tryAcquire("test:basic", 2, Duration.ofMinutes(1));
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();

        RateLimitDecision third = limiter.tryAcquire("test:basic", 2, Duration.ofMinutes(1));
        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isPositive();
    }

    @Test
    void theCounterCarriesATtlSoAWindowCannotLeak() {
        limiter.tryAcquire("test:ttl", 5, Duration.ofMinutes(10));

        assertThat(redis.getExpire("rl:test:ttl")).isBetween(1L, 600L);
    }

    @Test
    void peekLooksWithoutConsuming() {
        assertThat(limiter.peek("test:peek", 1, Duration.ofMinutes(1)).allowed()).isTrue();

        assertThat(limiter.tryAcquire("test:peek", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.peek("test:peek", 1, Duration.ofMinutes(1)).allowed()).isFalse();
        // 两次 peek 都不该改变账本：额度已经用完，再来一次仍然被拒。
        assertThat(limiter.tryAcquire("test:peek", 1, Duration.ofMinutes(1)).allowed()).isFalse();
    }

    @Test
    void aRejectedBatchDoesNotConsumeTheRemainingQuota() {
        assertThat(limiter.tryAcquire("test:batch", 4, 5, Duration.ofMinutes(1)).allowed()).isTrue();
        // 4 + 4 > 5：整批拒绝，且 4 不能留在账本里。
        assertThat(limiter.tryAcquire("test:batch", 4, 5, Duration.ofMinutes(1)).allowed()).isFalse();
        // 剩余 1 仍然可用，说明被拒批次回滚了。
        assertThat(limiter.tryAcquire("test:batch", 1, 5, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("test:batch", 1, 5, Duration.ofMinutes(1)).allowed()).isFalse();
    }

    @Test
    void aBatchLargerThanTheLimitIsRejectedBeforeWritingAnything() {
        assertThat(limiter.tryAcquire("test:huge", 9, 5, Duration.ofMinutes(1)).allowed()).isFalse();
        assertThat(redis.hasKey("rl:test:huge")).isFalse();
    }

    @Test
    void resetDropsOnlyThatKey() {
        limiter.tryAcquire("test:reset-a", 1, Duration.ofMinutes(1));
        limiter.tryAcquire("test:reset-b", 1, Duration.ofMinutes(1));

        limiter.reset("test:reset-a");

        assertThat(limiter.tryAcquire("test:reset-a", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("test:reset-b", 1, Duration.ofMinutes(1)).allowed()).isFalse();
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}

package com.hub.backend.infra.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 故障短路器。
 *
 * <p>限流对 Redis 是 fail-open 的，但「放行」不等于「没有代价」：命令超时设的是 1 秒，
 * Redis 挂掉期间**每一个**受限请求都要先等满这 1 秒才轮到放行。实测连发 3 次注册花了 3 秒，
 * 而 Redis 挂着可能持续几分钟 —— 相当于给所有登录/注册/发图接口加了一秒延迟。</p>
 *
 * <p>所以失败一次之后短路一段时间：窗口内的调用直接走降级路径，不再逐次撞那个超时；
 * 窗口结束再试一次，成功就恢复正常。窗口取 5 秒，是在「多花一次 1 秒」和「每次多花 1 秒」之间
 * 的折中 —— 前者是故障恢复的探测成本，后者是全站延迟。</p>
 *
 * <p>线程安全：一个原子时间戳，读多写少。窗口内的判断最多滞后几微秒，对限流无所谓。</p>
 */
final class RedisFailureSwitch {

    private static final Logger log = LoggerFactory.getLogger(RedisFailureSwitch.class);

    static final long DEFAULT_COOL_DOWN_MILLIS = 5_000L;

    private final AtomicLong retryAtNanos = new AtomicLong(0L);
    private final long coolDownNanos;

    RedisFailureSwitch() {
        this(DEFAULT_COOL_DOWN_MILLIS);
    }

    RedisFailureSwitch(long coolDownMillis) {
        this.coolDownNanos = coolDownMillis * 1_000_000L;
    }

    /** @return true 表示当前处于短路窗口，调用方应直接走降级路径 */
    boolean isOpen() {
        return System.nanoTime() < retryAtNanos.get();
    }

    void recordFailure() {
        retryAtNanos.set(System.nanoTime() + coolDownNanos);
    }

    void recordSuccess() {
        if (retryAtNanos.getAndSet(0L) != 0L) {
            // 恢复本身值得留一行：否则日志里只有失败，看不出什么时候好的。
            log.info("Redis is reachable again; rate limiting resumes normal behaviour");
        }
    }
}

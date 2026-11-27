package com.hub.backend.infra.redis;

import com.hub.backend.common.coordination.ChatRequestStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 进行中生成请求的共享账本。
 *
 * <p>键是 {@code chat:req:{账号}:{请求 id}}，值是状态。过期时间由注册表按
 * {@code app.chat.request-retention} 给：终态键多留几分钟，让「回答刚结束就点停止」还能拿到
 * 204 而不是 404。</p>
 *
 * <p>为什么不是把取消标志直接写进 Redis 让流式循环去读：一条回答要推几百个 chunk，每块读一次
 * Redis 在延迟和连接上都不可接受。所以真正的标志仍在持有连接的进程里，Redis 只负责「这条请求
 * 存在吗、什么状态、要不要广播取消」。</p>
 *
 * <p>Redis 不可用时一律按「查不到」处理（{@code state} 为空、{@code markCancelled} 为 false）。
 * 这时取消退化成单实例行为：打到持有者身上照样能停，打到别的实例上返回 404。</p>
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisChatRequestStateStore implements ChatRequestStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatRequestStateStore.class);

    static final String KEY_PREFIX = "chat:req:";
    static final String CANCELLED = "CANCELLED";

    /**
     * 只把 ACTIVE 翻成 CANCELLED，并保留剩余 TTL。
     *
     * <p>不能用 {@code SET ... XX}：那会顺手清掉 TTL，键就永远留在库里了。Redis 5 没有
     * {@code KEEPTTL}，所以显式读一次 TTL 再写回去；TTL 异常（-1/-2）时退回调用方给的兜底值。</p>
     */
    private static final String CANCEL_SCRIPT = """
            local v = redis.call('GET', KEYS[1])
            if v ~= 'ACTIVE' then
              return 0
            end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl <= 0 then
              ttl = tonumber(ARGV[1])
            end
            redis.call('SET', KEYS[1], 'CANCELLED', 'EX', ttl)
            return 1
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> cancelScript;
    /** 与限流共用同一套降级思路：Redis 不可用时别让每个建单请求都等一次超时。 */
    private final RedisFailureSwitch failureSwitch = new RedisFailureSwitch();

    public RedisChatRequestStateStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.cancelScript = RedisScript.of(CANCEL_SCRIPT, Long.class);
    }

    @Override
    public void markActive(String requestKey, Duration ttl) {
        if (failureSwitch.isOpen()) {
            return;
        }
        try {
            redis.opsForValue().set(key(requestKey), ChatRequestStateStore.ACTIVE, ttl);
            failureSwitch.recordSuccess();
        } catch (RuntimeException exception) {
            failureSwitch.recordFailure();
            log.warn("Redis chat state could not mark '{}' active: {}", requestKey, exception.getMessage());
        }
    }

    @Override
    public void markTerminal(String requestKey, String outcome, Duration ttl) {
        if (failureSwitch.isOpen()) {
            return;
        }
        try {
            redis.opsForValue().set(key(requestKey), outcome, ttl);
            failureSwitch.recordSuccess();
        } catch (RuntimeException exception) {
            failureSwitch.recordFailure();
            log.warn("Redis chat state could not mark '{}' {}: {}", requestKey, outcome, exception.getMessage());
        }
    }

    @Override
    public Optional<String> state(String requestKey) {
        if (failureSwitch.isOpen()) {
            return Optional.empty();
        }
        try {
            Optional<String> state = Optional.ofNullable(redis.opsForValue().get(key(requestKey)));
            failureSwitch.recordSuccess();
            return state;
        } catch (RuntimeException exception) {
            failureSwitch.recordFailure();
            log.warn("Redis chat state could not read '{}': {}", requestKey, exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean markCancelled(String requestKey, Duration fallbackTtl) {
        if (failureSwitch.isOpen()) {
            return false;
        }
        try {
            List<String> keys = Collections.singletonList(key(requestKey));
            Long flipped = redis.execute(cancelScript, keys,
                    String.valueOf(Math.max(1, fallbackTtl.toSeconds())));
            failureSwitch.recordSuccess();
            return flipped != null && flipped == 1L;
        } catch (RuntimeException exception) {
            failureSwitch.recordFailure();
            log.warn("Redis chat state could not cancel '{}': {}", requestKey, exception.getMessage());
            return false;
        }
    }

    private static String key(String requestKey) {
        return KEY_PREFIX + requestKey;
    }
}

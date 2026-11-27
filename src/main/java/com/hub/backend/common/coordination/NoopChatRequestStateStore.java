package com.hub.backend.common.coordination;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 单实例模式下的空实现：没有共享账本，注册表只看自己的内存。
 *
 * <p>它让 {@code ChatRequestRegistry} 不必到处写 {@code if (redisEnabled)}：本实现把「查不到」
 * 与「翻转失败」如实返回，注册表于是自然退回到原来的本地语义。</p>
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoopChatRequestStateStore implements ChatRequestStateStore {

    @Override
    public void markActive(String requestKey, Duration ttl) {
        // 内存模式没有跨实例可见性，写什么都没人读。
    }

    @Override
    public void markTerminal(String requestKey, String outcome, Duration ttl) {
        // 同上。
    }

    @Override
    public Optional<String> state(String requestKey) {
        return Optional.empty();
    }

    @Override
    public boolean markCancelled(String requestKey, Duration fallbackTtl) {
        return false;
    }
}

package com.hub.backend.common.coordination;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 单实例模式下的空广播：取消请求只可能打到当前进程，本地 map 里找不到就是真的没有。
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoopChatCancelBus implements ChatCancelBus {

    @Override
    public void publish(String requestKey) {
        // 单实例没有别的进程要通知。
    }

    @Override
    public void addListener(Consumer<String> listener) {
        // 永远不会收到信号。
    }
}

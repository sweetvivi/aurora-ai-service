package com.hub.backend.common.coordination;

import java.util.function.Consumer;

/**
 * 跨实例的「停止生成」广播。
 *
 * <p>流式生成的取消标志必须翻在**持有那条连接的那个进程**上：取消请求打到别的实例时，本地
 * 找不到条目，只能广播一条信号，让持有者自己把标志翻掉。</p>
 *
 * <p>投递语义是「尽力而为」（Redis pub/sub 是 at-most-once）：消息丢了，这一次停止就不会生效，
 * 生成会自然跑完。可以接受 —— 停止不是事务操作，用户重试一次即可；反过来为它引入确认与重放，
 * 复杂度远超收益。</p>
 */
public interface ChatCancelBus {

    /** 广播一次取消。 */
    void publish(String requestKey);

    /**
     * 订阅取消信号。回调在 Redis 的监听线程上执行，实现必须短小、线程安全。
     */
    void addListener(Consumer<String> listener);
}

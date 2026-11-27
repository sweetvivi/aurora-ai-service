package com.hub.backend.infra.redis;

import com.hub.backend.common.coordination.ChatCancelBus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 基于 Redis pub/sub 的跨实例取消广播。
 *
 * <p>订阅在启动时建立（{@code @PostConstruct}），因此实例只要活着就一直能收到信号；pub/sub 的
 * 消息不会补发，进程不在线期间发的取消就此丢失 —— 这正是「尽力而为」的定义，见
 * {@link ChatCancelBus}。</p>
 *
 * <p>自己发的消息也会被自己的订阅收到：持有者会命中本地条目并翻标志，不持有者查不到条目、
 * 什么也不做。所以广播端不需要知道「谁在跑」。</p>
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisChatCancelBus implements ChatCancelBus, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisChatCancelBus.class);

    /** 单一频道：消息里只有请求键，订阅方按本地 map 过滤，不值得为每个请求建频道。 */
    static final String CHANNEL = "chat:cancel";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public RedisChatCancelBus(StringRedisTemplate redis, RedisMessageListenerContainer container) {
        this.redis = redis;
        this.container = container;
    }

    @PostConstruct
    void subscribe() {
        container.addMessageListener(this, new ChannelTopic(CHANNEL));
        log.info("Subscribed to Redis channel '{}' for cross-instance chat cancellation", CHANNEL);
    }

    @Override
    public void publish(String requestKey) {
        try {
            redis.convertAndSend(CHANNEL, requestKey);
        } catch (RuntimeException exception) {
            // 广播失败不影响当前实例：如果生成正好跑在本地，本地那条路径已经翻过标志了。
            log.warn("Could not broadcast cancel for '{}': {}", requestKey, exception.getMessage());
        }
    }

    @Override
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String requestKey = new String(message.getBody(), StandardCharsets.UTF_8);
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(requestKey);
            } catch (RuntimeException exception) {
                // 一个监听方出错不能阻断其它监听方，也不能让 Redis 监听线程挂掉。
                log.warn("Chat cancel listener failed for '{}': {}", requestKey, exception.getMessage());
            }
        }
    }
}

package com.hub.backend.infra.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 监听容器。
 *
 * <p>Spring Boot 只自动配置连接工厂与 {@code RedisTemplate}，**不会**建监听容器；没有它，
 * pub/sub 的订阅方永远不会被调用。</p>
 *
 * <p>只在 {@code app.redis.enabled=true} 时创建：单实例部署不应该因为「配了 Redis 但没起」
 * 而在启动时陷入重连循环。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisListenerConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}

package com.hub.backend.infra.queue;

import java.time.LocalDateTime;

/**
 * 一条待投递的任务。
 *
 * <p>{@code payload} 对队列是**不透明字符串**：这里不解析、不校验，只负责原样存下来再原样交回。
 * 换成 RabbitMQ 之后它就是消息体，业务侧的编码方式不用跟着改。</p>
 *
 * @param queueName   队列名，与 {@link TaskHandler#queueName()} 对齐；消费者按它领取
 * @param payload     任务内容：单字段任务直接放原始字符串（如 storage key），多字段放 JSON
 * @param availableAt 最早可被领取的时间；{@code null} 表示立即可领（延迟任务用）
 * @param maxAttempts 最大尝试次数；{@code 0} 表示用全局默认值
 */
public record TaskMessage(String queueName, String payload,
                          LocalDateTime availableAt, int maxAttempts) {

    /** 立即可领、按全局默认重试次数。 */
    public static TaskMessage of(String queueName, String payload) {
        return new TaskMessage(queueName, payload, null, 0);
    }

    public TaskMessage availableAt(LocalDateTime availableAt) {
        return new TaskMessage(queueName, payload, availableAt, maxAttempts);
    }

    public TaskMessage withMaxAttempts(int maxAttempts) {
        return new TaskMessage(queueName, payload, availableAt, maxAttempts);
    }
}

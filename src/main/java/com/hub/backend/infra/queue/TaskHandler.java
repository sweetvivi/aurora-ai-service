package com.hub.backend.infra.queue;

/**
 * 队列消费者。
 *
 * <p>一个实现对应一个队列（{@link #queueName()}），由 {@link TaskQueueDispatcher} 自动发现并轮询，
 * 不需要额外注册。同一个队列名注册两个实现会在启动时直接失败 —— 那说明有人写错了队列名，
 * 让它在启动时炸，比在线上让两个处理器争同一批消息好排查。</p>
 *
 * <p>投递语义是至少一次：{@link #handle} 必须幂等。抛出的任何异常都会触发重试（上限由队列给出），
 * 因此「确定不该重试」的失败请在处理器内部消化并记日志，不要靠抛异常来表达。</p>
 */
public interface TaskHandler {

    /** 队列名，与 {@link TaskMessage#queueName()} 一致。 */
    String queueName();

    /**
     * 处理一条任务。
     *
     * @param payload 投递时的原始字符串
     * @throws RuntimeException 任何异常都视为本次失败，按退避策略重试
     */
    void handle(String payload);
}

package com.hub.backend.infra.queue;

import java.util.List;

/**
 * 任务队列端口。
 *
 * <p>业务只依赖这个接口。今天的实现是库里一张表（{@link DbTaskQueue}），换成 RabbitMQ 时新增
 * 一个实现即可，调用方（发布点、处理器）不用动 —— 方法语义刻意贴着 AMQP 走：</p>
 *
 * <ul>
 *   <li>{@link #publish} ↔ basicPublish，且必须能在业务事务里调用（发件箱语义）</li>
 *   <li>{@link #claim} ↔ basicConsume + 手动 ack 之前的「拿到消息」</li>
 *   <li>{@link #complete} ↔ basicAck</li>
 *   <li>{@link #fail} ↔ basicNack + 延迟重投 / 死信</li>
 *   <li>{@link #release} ↔ 主动归还（消费端拒收，不进死信）</li>
 * </ul>
 *
 * <p>投递语义是 **至少一次**：处理器可能收到重复消息（租约过期后重投），因此实现必须是幂等的。</p>
 */
public interface TaskQueue {

    /**
     * 投递一条任务。
     *
     * <p>在调用方的事务里执行：业务回滚，消息跟着消失；业务提交，消息一定在库里。
     * 因此它不能自己开新事务，也不能推迟到 afterCommit。</p>
     *
     * <p>带去重键的消息若已存在（无论状态），本次投递被静默忽略。</p>
     */
    void publish(TaskMessage message);

    /**
     * 领取至多 {@code batch} 条可执行任务，并把它们标记为已领取。
     *
     * @param queueName 队列名
     * @param workerId  本次领取的 worker 标识，写进租约
     * @param batch     上限；实际返回可能更少
     */
    List<ClaimedTask> claim(String queueName, String workerId, int batch);

    /** 收尾为成功。租约已被别人接管时命中 0 行，属正常情况。 */
    void complete(ClaimedTask task);

    /**
     * 收尾为失败。
     *
     * @param retryable   false 直接判死；true 且未到上限则按退避重投
     * @param error       写入 {@code last_error}，用于排查
     */
    void fail(ClaimedTask task, String error, boolean retryable);

    /** 主动归还：状态回到 pending 并立即可领，同时把领取时计的那一次尝试还回去（这条消息没跑过）。 */
    void release(ClaimedTask task);

    /**
     * 回收租约过期的任务。
     *
     * <p>没到上限的回到 pending（{@code attempts} 已经在上次 claim 时计过），到上限的判失败。
     * 返回处理的行数，仅用于日志。</p>
     */
    int releaseExpired();
}

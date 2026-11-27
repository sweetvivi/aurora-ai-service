package com.hub.backend.infra.queue;

import com.hub.backend.common.config.properties.QueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轮询队列并把任务交给处理器。
 *
 * <p>一个调度线程负责「领」，处理器跑在专用线程池里「做」，两者分开：某个处理器慢，不应该拖住
 * 其它队列的领取；处理线程池满了就把刚领到的消息还回去，等下一轮 —— 领了不做的消息会白占一个
 * 租约，等于把这一轮额度浪费掉。</p>
 *
 * <p>队列名到处理器的映射在启动时建好，同名处理器直接让应用起不来：那一定是复制粘贴写错了
 * 队列名，线上两个消费者争同一批消息远比启动失败难查。</p>
 */
@Component
public class TaskQueueDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueDispatcher.class);

    private final TaskQueue queue;
    private final QueueProperties properties;
    private final AsyncTaskExecutor executor;
    private final Map<String, TaskHandler> handlers;
    /** 本进程的 worker 标识，写进租约；重启后换新的。 */
    private final String workerId =
            "queue-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    public TaskQueueDispatcher(TaskQueue queue,
                               QueueProperties properties,
                               @Qualifier("taskQueueExecutor") AsyncTaskExecutor executor,
                               List<TaskHandler> handlers) {
        this.queue = queue;
        this.properties = properties;
        this.executor = executor;
        Map<String, TaskHandler> byQueue = new LinkedHashMap<>();
        for (TaskHandler handler : handlers) {
            TaskHandler previous = byQueue.put(handler.queueName(), handler);
            if (previous != null) {
                throw new IllegalStateException("两个处理器订阅了同一个队列：" + handler.queueName());
            }
        }
        this.handlers = byQueue;
    }

    /**
     * 一轮：先回收过期租约，再逐队列领取并派发。
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：一轮结束后再计时，数据库慢的时候不会叠加。</p>
     */
    @Scheduled(
            fixedDelayString = "${app.queue.poll-interval:2s}",
            initialDelayString = "${app.queue.initial-delay:10s}")
    public void runOnce() {
        if (!properties.isEnabled() || handlers.isEmpty()) {
            return;
        }
        try {
            int reclaimed = queue.releaseExpired();
            if (reclaimed > 0) {
                log.warn("Reclaimed {} task(s) whose lease expired before ack", reclaimed);
            }
        } catch (RuntimeException exception) {
            // 回收失败不该挡住本轮领取：过期的消息下一轮还会被扫到。
            log.warn("Reclaiming expired tasks failed: {}", exception.getMessage());
        }

        for (Map.Entry<String, TaskHandler> entry : handlers.entrySet()) {
            try {
                drain(entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                log.warn("Claiming from queue {} failed: {}", entry.getKey(), exception.getMessage());
            }
        }
    }

    /**
     * 连续领几批，直到队列见底或线程池拒绝。
     *
     * <p>只领一批会把吞吐钉在 {@code batch-size / poll-interval}：默认 10 条 / 2 秒 = 5 条/秒，
     * 处理器再快也没用。积压时（Redis 抖动、重启后一堆待办）这个上限就是恢复速度本身。</p>
     *
     * <p>两个中止条件都不能少：队列不足一批说明已经见底；线程池拒收说明再领也只会马上还回去 ——
     * 继续领会造成「领了 → 还回去 → 再领」的空转，把数据库打热。</p>
     */
    private void drain(String queueName, TaskHandler handler) {
        int batchSize = Math.max(1, properties.getBatchSize());
        int rounds = Math.max(1, properties.getMaxBatchesPerSweep());
        for (int round = 0; round < rounds; round++) {
            List<ClaimedTask> claimed = queue.claim(queueName, workerId, batchSize);
            if (claimed.isEmpty()) {
                return;
            }
            boolean acceptedAll = true;
            for (ClaimedTask task : claimed) {
                if (!dispatch(handler, task)) {
                    acceptedAll = false;
                }
            }
            if (!acceptedAll || claimed.size() < batchSize) {
                return;
            }
        }
    }

    /**
     * @return 是否被线程池接收；{@code false} 表示池已满，本队列本轮应当停止领取
     */
    private boolean dispatch(TaskHandler handler, ClaimedTask task) {
        try {
            executor.execute(() -> {
                try {
                    handler.handle(task.payload());
                    queue.complete(task);
                } catch (RuntimeException exception) {
                    // 处理器自己消化「不该重试」的失败；走到这里就是可重试的，交给退避。
                    queue.fail(task, describe(exception), true);
                }
            });
            return true;
        } catch (TaskRejectedException exception) {
            // 线程池满了：还回去，下一轮再试。留在 claimed 状态只会白占一个租约。
            queue.release(task);
            log.debug("Task queue worker pool is saturated; task {} returned to queue {}", task.id(), task.queueName());
            return false;
        }
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + message;
    }
}

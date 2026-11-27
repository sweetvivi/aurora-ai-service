package com.hub.backend.infra.queue;

import com.hub.backend.common.config.properties.QueueProperties;
import com.hub.backend.infra.queue.entity.TaskQueueEntryEntity;
import com.hub.backend.infra.queue.repository.TaskQueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 单表的队列实现。
 *
 * <p>为什么是表而不是消息中间件：这里的量级是「每小时几十条副作用任务」，引入 RabbitMQ 只会
 * 多一个要备份、要升级、要监控的组件。表的好处是消息与业务数据在同一个事务里 —— 业务回滚，
 * 消息就不会存在，不需要发件箱中继去补。真到了需要扇出/百万级堆积的时候，换实现即可，
 * {@link TaskQueue} 的语义是照着 AMQP 定的。</p>
 *
 * <p>实现细节：</p>
 * <ul>
 *   <li>领取是「先查候选 → 逐行 CAS」，只有 UPDATE 命中 1 行的才算拿到，多实例下不会重复执行。</li>
 *   <li>{@code attempts} 在领取时 +1，所以「领了但没 ack 就死了」也算一次尝试，不会无限重投。</li>
 *   <li>可重试的失败按 {@code base × 2^(n-1)} 退避，封顶 {@code retry-max-delay}；
 *       退避写在 {@code available_at} 上，而不是 sleep 线程。</li>
 * </ul>
 */
@Component
public class DbTaskQueue implements TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(DbTaskQueue.class);
    private static final int MAX_ERROR_CHARS = 2048;

    private final TaskQueueEntryRepository entries;
    private final QueueProperties properties;

    public DbTaskQueue(TaskQueueEntryRepository entries, QueueProperties properties) {
        this.entries = entries;
        this.properties = properties;
    }

    @Override
    public void publish(TaskMessage message) {
        LocalDateTime now = microsNow();
        LocalDateTime availableAt = message.availableAt() == null ? now : message.availableAt();
        int maxAttempts = message.maxAttempts() > 0 ? message.maxAttempts() : properties.getMaxAttempts();
        entries.save(new TaskQueueEntryEntity(
                message.queueName(), message.payload(), availableAt, maxAttempts));
    }

    @Override
    public List<ClaimedTask> claim(String queueName, String workerId, int batch) {
        if (batch <= 0) {
            return List.of();
        }
        LocalDateTime now = microsNow();
        LocalDateTime leaseUntil = now.plus(properties.getLeaseTtl());
        List<TaskQueueEntryEntity> candidates =
                entries.findClaimable(queueName, now, PageRequest.of(0, batch));
        List<ClaimedTask> claimed = new ArrayList<>(candidates.size());
        for (TaskQueueEntryEntity candidate : candidates) {
            if (entries.claim(candidate.getId(), workerId, leaseUntil, now) == 1) {
                claimed.add(new ClaimedTask(
                        candidate.getId(),
                        queueName,
                        candidate.getPayload(),
                        candidate.getAttempts() + 1,
                        candidate.getMaxAttempts(),
                        workerId));
            }
        }
        return claimed;
    }

    @Override
    public void complete(ClaimedTask task) {
        int updated = entries.markDone(task.id(), task.leaseOwner(), LocalDateTime.now());
        if (updated == 0) {
            // 租约过期后别人接管了这条消息：本次结果作废是正常的，重复投递本来就允许。
            log.debug("Task {} in queue {} was taken over before ack", task.id(), task.queueName());
        }
    }

    @Override
    public void fail(ClaimedTask task, String error, boolean retryable) {
        LocalDateTime now = microsNow();
        String message = truncate(error);
        boolean exhausted = task.attempts() >= task.maxAttempts();
        if (!retryable || exhausted) {
            entries.markFailed(task.id(), task.leaseOwner(), message, now);
            log.warn("Task {} in queue {} failed permanently after {} attempt(s): {}",
                    task.id(), task.queueName(), task.attempts(), message);
            return;
        }
        LocalDateTime availableAt = now.plus(backoff(task.attempts()));
        entries.requeue(task.id(), task.leaseOwner(), availableAt, message, now);
        log.warn("Task {} in queue {} failed (attempt {}/{}), retrying after {}: {}",
                task.id(), task.queueName(), task.attempts(), task.maxAttempts(), backoff(task.attempts()), message);
    }

    @Override
    public void release(ClaimedTask task) {
        entries.release(task.id(), task.leaseOwner(), LocalDateTime.now());
    }

    @Override
    public int releaseExpired() {
        LocalDateTime now = microsNow();
        int failed = entries.expireToFailed("租约过期（执行者未在租约内完成）", now);
        int requeued = entries.expireToPending(now);
        return failed + requeued;
    }

    /**
     * 取当前时间并截到微秒。
     *
     * <p>列是 {@code DATETIME(6)}，写进去的纳秒会被 <b>四舍五入</b> 到微秒：Windows 上
     * {@code now()} 是 100ns 粒度，末尾 500/600/700/800/900 的值会进位成下一秒的微秒。
     * 而查询条件 {@code availableAt <= :now} 的参数是用原值比较的 —— 于是「刚发布、立即可领」
     * 的那一行，在同一个微秒内查不到，要等下一轮扫描。表现就是偶发地「发布后立刻 claim 得到空」。
     * 两端都截到微秒，比较才是精确的。</p>
     */
    private static LocalDateTime microsNow() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 第 1 次失败等 base，第 2 次等 2×base，以此类推，封顶 {@code retry-max-delay}。 */
    private Duration backoff(int attempts) {
        Duration base = properties.getRetryBaseDelay();
        Duration max = properties.getRetryMaxDelay();
        int shift = Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = base.multipliedBy(1L << shift);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS);
    }
}

package com.hub.backend.infra.queue;

import com.hub.backend.infra.queue.entity.TaskQueueEntryEntity;
import com.hub.backend.infra.queue.repository.TaskQueueEntryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 队列指标：积压多深、等了多久。
 *
 * <p>在这之前队列是个黑盒 —— 出问题只能翻日志猜，而且「积压 300 条」和「积压 3 条」在日志里长得一样。
 * 两个问题的答案完全取决于「有没有东西在等」以及「等了多久」，所以只做这两个指标：</p>
 *
 * <ul>
 *   <li>{@value #PENDING} —— 待办条数（积压深度）</li>
 *   <li>{@value #OLDEST_PENDING_AGE} —— 最老一条待办等了多久（积压是否在恶化）</li>
 *   <li>{@value #CLAIMED} / {@value #FAILED} —— 在跑的、已判死的，排障时区分「卡住」与「一直失败」</li>
 * </ul>
 *
 * <p><b>指标不能自己去查库</b>：Prometheus 这类抓取端每 15 秒抓一次，每次抓取都执行聚合查询，
 * 相当于给数据库加了一份固定负载，而且抓取频率不由我们控制。所以这里用定时刷新：每 30 秒查一次，
 * 指标读的是内存里的原子数，抓取再密也只是读几个 long。</p>
 *
 * <p>队列空着也要有 0：启动时就为所有已注册的消费方建好 gauge。否则告警规则里那条序列在
 * 「一切正常」时不存在，`absent()` / `rate()` 之类会误报或干脆不触发。</p>
 */
@Component
public class TaskQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueMetrics.class);

    static final String PENDING = "taskqueue.pending";
    static final String CLAIMED = "taskqueue.claimed";
    static final String FAILED = "taskqueue.failed";
    static final String OLDEST_PENDING_AGE = "taskqueue.oldest.pending.age";

    private final TaskQueueEntryRepository entries;
    private final MeterRegistry registry;
    private final Map<String, QueueState> states = new ConcurrentHashMap<>();

    public TaskQueueMetrics(TaskQueueEntryRepository entries, MeterRegistry registry,
                            List<TaskHandler> handlers) {
        this.entries = entries;
        this.registry = registry;
        handlers.forEach(handler -> stateFor(handler.queueName()));
    }

    /**
     * 刷新一次快照。
     *
     * <p>两条聚合查询（按队列×状态计数、按队列取最老待办），队列再多也是两条 —— 不做成
     * 「每个队列各查一次」，那样查询数会随消费方数量线性增长。</p>
     */
    @Scheduled(
            fixedDelayString = "${app.queue.metrics-refresh:30s}",
            initialDelayString = "${app.queue.metrics-initial-delay:20s}")
    public void refresh() {
        try {
            LocalDateTime now = LocalDateTime.now();
            Map<String, QueueSnapshot> snapshot = new HashMap<>();

            for (Object[] row : entries.countGroupedByQueueAndStatus()) {
                String queueName = String.valueOf(row[0]);
                String status = String.valueOf(row[1]);
                long count = ((Number) row[2]).longValue();
                snapshot.computeIfAbsent(queueName, name -> new QueueSnapshot()).set(status, count);
            }
            for (Object[] row : entries.oldestPendingCreatedAt()) {
                String queueName = String.valueOf(row[0]);
                LocalDateTime oldest = (LocalDateTime) row[1];
                if (oldest != null) {
                    long ageSeconds = Math.max(0L, Duration.between(oldest, now).getSeconds());
                    snapshot.computeIfAbsent(queueName, name -> new QueueSnapshot()).oldestAge = ageSeconds;
                }
            }

            // 先归零再写入：积压清空这件事本身必须体现在指标上，漏掉会让曲线一直停在旧值。
            states.values().forEach(QueueState::reset);
            snapshot.forEach((queueName, values) -> stateFor(queueName).copyFrom(values));
        } catch (RuntimeException exception) {
            // 指标刷新失败不能影响业务；保留上一轮的值，下一轮再试。
            log.warn("Could not refresh task queue metrics: {}", exception.getMessage());
        }
    }

    /** 每个队列的 gauge 只注册一次，之后再拿同一个 state 对象改数值。 */
    private QueueState stateFor(String queueName) {
        return states.computeIfAbsent(queueName, name -> {
            QueueState state = new QueueState();
            Gauge.builder(PENDING, state, QueueState::pending).tag("queue", name).register(registry);
            Gauge.builder(CLAIMED, state, QueueState::claimed).tag("queue", name).register(registry);
            Gauge.builder(FAILED, state, QueueState::failed).tag("queue", name).register(registry);
            Gauge.builder(OLDEST_PENDING_AGE, state, QueueState::oldestPendingAgeSeconds)
                    .tag("queue", name).register(registry);
            return state;
        });
    }

    private static final class QueueState {

        private final AtomicLong pending = new AtomicLong();
        private final AtomicLong claimed = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

        long pending() {
            return pending.get();
        }

        long claimed() {
            return claimed.get();
        }

        long failed() {
            return failed.get();
        }

        long oldestPendingAgeSeconds() {
            return oldestPendingAgeSeconds.get();
        }

        void reset() {
            pending.set(0);
            claimed.set(0);
            failed.set(0);
            oldestPendingAgeSeconds.set(0);
        }

        void copyFrom(QueueSnapshot snapshot) {
            pending.set(snapshot.pending);
            claimed.set(snapshot.claimed);
            failed.set(snapshot.failed);
            oldestPendingAgeSeconds.set(snapshot.oldestAge);
        }
    }

    private static final class QueueSnapshot {

        private long pending;
        private long claimed;
        private long failed;
        private long oldestAge;

        void set(String status, long count) {
            switch (status) {
                case TaskQueueEntryEntity.STATUS_PENDING -> pending = count;
                case TaskQueueEntryEntity.STATUS_CLAIMED -> claimed = count;
                case TaskQueueEntryEntity.STATUS_FAILED -> failed = count;
                default -> {
                    // done 不进指标：它只会无限增长，且不反映「有没有积压」。
                }
            }
        }
    }
}

package com.hub.backend.common.logging.audit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计记录异步批量写入 {@code audit_logs}。
 *
 * <p>与 {@code RequestLogWriter} 同一套取舍（不抢连接池、满队列丢弃、失败只记数），不共用实现
 * 是因为两张表列不同、保留期不同、开关节奏也不同；为省一百行把两者抽象成一个泛型写入器，
 * 会让「审计能不能丢」这个关键语义被模糊掉——访问日志可以丢，审计日志不该悄无声息地丢。</p>
 *
 * <p>因此这里对丢弃的处理更严：一旦出现丢弃，除了 WARN 计数，还会**额外**在
 * {@code backend-error.log} 里留一条带操作名的记录，便于事后按 operation 追责与补录。</p>
 */
@Component
public class AuditLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogWriter.class);

    /** 列顺序与 {@link #toRow} 严格一致；created_at 显式赋值以兼容 H2（无列默认值）。 */
    private static final String INSERT_SQL = """
            INSERT INTO audit_logs (
                occurred_at, trace_id, action, target, target_id, actor_type, actor_id,
                client_ip, user_agent, http_method, route, before_json, after_json,
                changed_json, outcome, error_message, duration_ms, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final long REPORT_INTERVAL_MS = 60_000L;

    private final BlockingQueue<AuditLogEntity> queue;
    private final JdbcTemplate jdbc;
    private final AuditLogProperties properties;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile boolean running = true;
    private Thread worker;
    private long lastReportAt = System.currentTimeMillis();

    public AuditLogWriter(DataSource dataSource, AuditLogProperties properties) {
        this.properties = properties;
        this.jdbc = new JdbcTemplate(dataSource);
        this.queue = new ArrayBlockingQueue<>(Math.max(64, properties.getQueueCapacity()));
    }

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.warn("Audit logging is disabled (app.audit-log.enabled=false); writer not started");
            return;
        }
        worker = new Thread(this::drainLoop, "audit-log-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("Audit log writer started: queue={}, batch={}, retention={}d",
                properties.getQueueCapacity(), properties.getBatchSize(), properties.getRetentionDays());
    }

    /** 非阻塞入队，由切面在业务线程调用。 */
    public boolean offer(AuditLogEntity entry) {
        if (entry == null || !properties.isEnabled()) {
            return false;
        }
        if (queue.offer(entry)) {
            return true;
        }
        dropped.incrementAndGet();
        // 审计丢失与访问日志丢失不是一个量级的问题：这条 WARN 会进 backend-error.log，
        // 带上足够信息用于事后补录，而不是只留一个计数器。
        log.warn("Audit record dropped (queue full): action={} target={} targetId={} actor={}",
                entry.getAction(), entry.getTarget(), entry.getTargetId(), entry.getActorId());
        return false;
    }

    private void drainLoop() {
        List<AuditLogEntity> batch = new ArrayList<>(Math.max(8, properties.getBatchSize()));
        while (running || !queue.isEmpty()) {
            try {
                AuditLogEntity head = queue.poll(properties.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (head == null) {
                    reportIfDue();
                    continue;
                }
                batch.add(head);
                queue.drainTo(batch, Math.max(0, properties.getBatchSize() - batch.size()));
                flush(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                failed.addAndGet(Math.max(1, batch.size()));
                log.warn("Audit log writer failed: {}", ex.toString());
            } finally {
                batch.clear();
            }
            reportIfDue();
        }
        log.info("Audit log writer stopped: written={}, dropped={}, failed={}",
                written.get(), dropped.get(), failed.get());
    }

    private void flush(List<AuditLogEntity> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (AuditLogEntity e : batch) {
            rows.add(toRow(e));
        }
        try {
            jdbc.batchUpdate(INSERT_SQL, rows);
            written.addAndGet(rows.size());
        } catch (Exception ex) {
            failed.addAndGet(rows.size());
            log.warn("Audit log batch insert failed ({} rows): {}", rows.size(), ex.toString());
        }
    }

    private Object[] toRow(AuditLogEntity e) {
        return new Object[]{
                e.getOccurredAt(),
                e.getTraceId(),
                e.getAction(),
                e.getTarget(),
                e.getTargetId(),
                e.getActorType(),
                e.getActorId(),
                e.getClientIp(),
                e.getUserAgent(),
                e.getHttpMethod(),
                e.getRoute(),
                e.getBeforeJson(),
                e.getAfterJson(),
                e.getChangedJson(),
                e.getOutcome(),
                e.getErrorMessage(),
                e.getDurationMs(),
                LocalDateTime.now()
        };
    }

    private void reportIfDue() {
        if (dropped.get() == 0 && failed.get() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReportAt < REPORT_INTERVAL_MS) {
            return;
        }
        lastReportAt = now;
        log.warn("Audit log backlog: written={}, dropped={}, failed={}, queueSize={}",
                written.get(), dropped.get(), failed.get(), queue.size());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker == null) {
            return;
        }
        try {
            worker.join(3000);
            if (worker.isAlive()) {
                log.warn("Audit log writer did not stop within 3s; {} records may be lost", queue.size());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

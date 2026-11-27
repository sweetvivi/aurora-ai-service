package com.hub.backend.common.logging;

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
 * 把请求日志异步、批量写进 {@code request_logs}。
 *
 * <p><b>为什么不直接 JPA {@code save}</b>：</p>
 * <ul>
 *   <li>本项目连接池只有 28 条（容量是按「压测三档吞吐」推导出来的，见 {@code application.yml}
 *       的注释）。日志若走常规事务，会跟业务抢连接，重演「活跃连接长停 10/10、吞吐钉在 550RPS」
 *       那次瓶颈；</li>
 *   <li>Hibernate 的持久化上下文、flush 时序、一级缓存对一个「只增不改」的表全是纯开销；</li>
 *   <li>批量插入才能把 100 次往返压成 1 次，这是主要成本项。</li>
 * </ul>
 *
 * <p><b>为什么满队列丢弃而不是阻塞</b>：日志是旁路能力，不能成为业务链路的故障点。
 * 队列满说明写入已经跟不上，此时阻塞接口只会把数据库的慢放大成用户可感知的超时。
 * 丢弃量会被统计并在每分钟的报告里打 WARN，配套告警即可发现。</p>
 *
 * <p>写入失败（数据库不可用、字段超长等）同样只记数不抛：一条日志写不进去，
 * 不应该让已经成功的请求在最后一步变成 500。</p>
 */
@Component
public class RequestLogWriter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    /**
     * 显式列出 {@code created_at} 而不是依赖数据库默认值：H2 profile 下建表由 Hibernate
     * 按实体生成，{@code @CreationTimestamp} 不会变成列默认值，省略该列会直接违反非空约束。
     */
    private static final String INSERT_SQL = """
            INSERT INTO request_logs (
                trace_id, request_time, level, user_id, site_id, client_ip, user_agent,
                browser, os, http_method, route, uri, query_string, request_body,
                response_status, outcome, error_code, error_message, error_details,
                duration_ms, upstream_json, biz_type, biz_id, created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final long REPORT_INTERVAL_MS = 60_000L;

    private final BlockingQueue<RequestLogEntity> queue;
    private final JdbcTemplate jdbc;
    private final RequestLogProperties properties;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile boolean running = true;
    private Thread worker;
    private long lastReportAt = System.currentTimeMillis();

    public RequestLogWriter(DataSource dataSource, RequestLogProperties properties) {
        this.properties = properties;
        this.jdbc = new JdbcTemplate(dataSource);
        this.queue = new ArrayBlockingQueue<>(Math.max(64, properties.getQueueCapacity()));
    }

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("Request logging is disabled (app.request-log.enabled=false); writer not started");
            return;
        }
        worker = new Thread(this::drainLoop, "request-log-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("Request log writer started: queue={}, batch={}, retention={}d",
                properties.getQueueCapacity(), properties.getBatchSize(), properties.getRetentionDays());
    }

    /**
     * 非阻塞入队。由过滤器在请求出站时调用，绝不能在这里等待。
     *
     * @return 是否成功入队；{@code false} 表示已丢弃（队列满或写入器未启动）
     */
    public boolean offer(RequestLogEntity entry) {
        if (entry == null || !properties.isEnabled()) {
            return false;
        }
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    private void drainLoop() {
        List<RequestLogEntity> batch = new ArrayList<>(Math.max(8, properties.getBatchSize()));
        while (running || !queue.isEmpty()) {
            try {
                RequestLogEntity head = queue.poll(properties.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (head == null) {
                    reportIfDue();
                    continue;
                }
                batch.add(head);
                // 一次尽量多取，把 N 次往返压成 1 次。
                queue.drainTo(batch, Math.max(0, properties.getBatchSize() - batch.size()));
                flush(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                // 兜底：任何未预期的异常都不能让写入线程退出，否则后续日志全部静默丢失。
                failed.addAndGet(Math.max(1, batch.size()));
                log.warn("Request log writer failed: {}", ex.toString());
            } finally {
                batch.clear();
            }
            reportIfDue();
        }
        log.info("Request log writer stopped: written={}, dropped={}, failed={}",
                written.get(), dropped.get(), failed.get());
    }

    private void flush(List<RequestLogEntity> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (RequestLogEntity e : batch) {
            rows.add(toRow(e));
        }
        try {
            jdbc.batchUpdate(INSERT_SQL, rows);
            written.addAndGet(rows.size());
        } catch (Exception ex) {
            // 单批失败整批作废：日志表可以缺行，但不能因为一条脏数据反复重试卡住队列。
            failed.addAndGet(rows.size());
            log.warn("Request log batch insert failed ({} rows): {}", rows.size(), ex.toString());
        }
    }

    private Object[] toRow(RequestLogEntity e) {
        return new Object[]{
                e.getTraceId(),
                e.getRequestTime(),
                e.getLevel(),
                e.getUserId(),
                e.getSiteId(),
                e.getClientIp(),
                e.getUserAgent(),
                e.getBrowser(),
                e.getOs(),
                e.getHttpMethod(),
                e.getRoute(),
                e.getUri(),
                e.getQueryString(),
                e.getRequestBody(),
                e.getResponseStatus(),
                e.getOutcome(),
                e.getErrorCode(),
                e.getErrorMessage(),
                e.getErrorDetails(),
                e.getDurationMs(),
                e.getUpstreamJson(),
                e.getBizType(),
                e.getBizId(),
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
        // 这条 WARN 本身就是告警信号：出现即说明日志写入跟不上或数据库出问题。
        log.warn("Request log backlog: written={}, dropped={}, failed={}, queueSize={}",
                written.get(), dropped.get(), failed.get(), queue.size());
    }

    /** 关停时把队列里剩下的写完再退出，最多等 3 秒。 */
    @PreDestroy
    void stop() {
        running = false;
        if (worker == null) {
            return;
        }
        try {
            worker.join(3000);
            if (worker.isAlive()) {
                log.warn("Request log writer did not stop within 3s; {} entries may be lost", queue.size());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

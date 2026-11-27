package com.hub.backend.common.logging.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * 清理超过保留期的审计记录。
 *
 * <p>与 {@code RequestLogCleanupScheduler} 相同的分批策略（短事务、单批上限），
 * 只是保留期长得多：默认 180 天。审计记录回答的是「三个月前谁把余额改成了多少」，
 * 按访问日志的 7 天清掉就等于没有——两张表的保留期必须分开看，别用一个开关一起改。</p>
 *
 * <p>固定每天 03:40 执行：审计表一天只有几十到几百行，不需要小时级清理；
 * 与请求日志清理（每小时第 5 分钟）也不会撞在同一时刻抢连接池。</p>
 */
@Component
public class AuditLogCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditLogCleanupScheduler.class);

    /** 子查询多包一层是 MySQL 限制：DELETE 的目标表不能直接出现在同语句的子查询里。 */
    private static final String PURGE_SQL = """
            DELETE FROM audit_logs
            WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM audit_logs WHERE occurred_at < ? ORDER BY id LIMIT ?
                ) expired
            )
            """;

    private static final int MAX_ROUNDS = 200;

    private final JdbcTemplate jdbc;
    private final AuditLogProperties properties;

    public AuditLogCleanupScheduler(DataSource dataSource, AuditLogProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @Scheduled(cron = "0 40 3 * * *")
    public void purgeExpired() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, properties.getRetentionDays()));
        int batchSize = Math.max(100, properties.getPurgeBatchSize());
        long totalDeleted = 0;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            int deleted;
            try {
                deleted = jdbc.update(PURGE_SQL, cutoff, batchSize);
            } catch (Exception ex) {
                log.warn("Audit log purge aborted after {} rows: {}", totalDeleted, ex.toString());
                return;
            }
            totalDeleted += deleted;
            if (deleted < batchSize) {
                if (totalDeleted > 0) {
                    log.info("Audit log purge removed {} rows older than {}", totalDeleted, cutoff);
                }
                return;
            }
        }
        log.warn("Audit log purge hit the round limit ({} batches, {} rows); remainder deferred to tomorrow",
                MAX_ROUNDS, totalDeleted);
    }
}

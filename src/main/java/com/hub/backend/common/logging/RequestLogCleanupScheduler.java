package com.hub.backend.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * 清理超过保留期的请求日志。
 *
 * <p>单独写这个类的原因是 {@code request_logs} 只增不减：按中等流量估算，一天几百万行很常见，
 * 不清理的话索引会持续膨胀，最终把「查一次日志」变成「等一次全表扫描」。</p>
 *
 * <p><b>为什么分批删而不是一条 DELETE</b>：一次性删掉几十万行会长时间持有行锁、写满 undo，
 * 在有主从复制或备份窗口的环境里直接表现为业务写入抖动。分批让每批都是一个短事务，
 * 单批上限可控。</p>
 *
 * <p><b>为什么每小时跑一次而不是每天一次</b>：保留期是 7 天，每天过期量约占全表 1/7。
 * 一天只扫一次等于把 24 小时的过期量攒在一个事务窗口里删；改成每小时删 1/24，
 * 总写入量不变，但单次事务更短、更容易被索引命中，也让「7 天」真的是 7 天而不是 7~8 天。</p>
 *
 * <p>错开整点（每小时第 5 分钟）执行：整点是各类定时任务和对账的高峰。
 * 删不完的留到下一个小时继续，不做追赶——清理是维护性任务，不值得为它增加复杂度。</p>
 */
@Component
public class RequestLogCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RequestLogCleanupScheduler.class);

    /**
     * 子查询多包一层是 MySQL 的硬性要求：{@code DELETE} 的目标表不能直接出现在同一条语句的
     * 子查询里（会报 "You can't specify target table for update in FROM clause"）。
     */
    private static final String PURGE_SQL = """
            DELETE FROM request_logs
            WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM request_logs WHERE request_time < ? ORDER BY id LIMIT ?
                ) expired
            )
            """;

    /** 单次执行最多删除的批次数，防止保留天数配错时一次删掉整张表。 */
    private static final int MAX_ROUNDS = 200;

    private final JdbcTemplate jdbc;
    private final RequestLogProperties properties;

    public RequestLogCleanupScheduler(DataSource dataSource, RequestLogProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    /** 每小时第 5 分钟执行，避开整点任务高峰。 */
    @Scheduled(cron = "0 5 * * * *")
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
                // 清理失败不影响业务，但要能被发现：反复出现说明表已经太大或数据库有压力。
                log.warn("Request log purge aborted after {} rows: {}", totalDeleted, ex.toString());
                return;
            }
            totalDeleted += deleted;
            if (deleted < batchSize) {
                if (totalDeleted > 0) {
                    log.info("Request log purge removed {} rows older than {}", totalDeleted, cutoff);
                } else if (round == 0) {
                    // 空跑也留一行：不然「每小时到底有没有在清理」只能靠猜，出问题时要翻代码。
                    // 用 debug 而不是 info——正常情况下每小时都空跑，info 会变成噪音。
                    log.debug("Request log purge: nothing older than {}", cutoff);
                }
                return;
            }
        }
        log.warn("Request log purge hit the round limit ({} batches, {} rows); remainder deferred to the next run",
                MAX_ROUNDS, totalDeleted);
    }
}

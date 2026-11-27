package com.hub.backend.common.logging;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁住访问日志的过期清理行为。
 *
 * <p>这里的删除是物理删除（{@code DELETE}），删错没有回收站，所以三个边界必须单独断言：
 * 保留期内的行不能动、开关关掉完全不动、积压超过一批时要分多批删完而不是只删一批。</p>
 *
 * <p>用 H2 的 MySQL 兼容模式跑真实 SQL：清理语句是 MySQL 方言（DELETE 目标表不能直接出现在
 * 子查询里，因此多包了一层派生表），纯 mock 的测试锁不住这类语法问题。</p>
 */
class RequestLogCleanupSchedulerTest {

    private JdbcTemplate jdbc;
    private RequestLogProperties properties;
    private RequestLogCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        // 每个用例独立库名，避免同一个 JVM 里前面的用例残留数据干扰计数。
        dataSource.setURL("jdbc:h2:mem:request-log-purge-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE request_logs (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    request_time TIMESTAMP NOT NULL,
                    route VARCHAR(255)
                )
                """);
        properties = new RequestLogProperties();
        scheduler = new RequestLogCleanupScheduler(dataSource, properties);
    }

    @Test
    void deletesOnlyRowsOlderThanSevenDays() {
        insert(3, LocalDateTime.now().minusDays(8));
        insert(2, LocalDateTime.now().minusDays(6));

        scheduler.purgeExpired();

        assertEquals(2, count());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_logs WHERE request_time < ?",
                Integer.class, Timestamp.valueOf(LocalDateTime.now().minusDays(7))));
    }

    @Test
    void retentionWindowFollowsConfiguration() {
        properties.setRetentionDays(1);
        insert(1, LocalDateTime.now().minusDays(2));
        insert(1, LocalDateTime.now().minusHours(12));

        scheduler.purgeExpired();

        // 配 1 天就必须按 1 天删，而不是沿用默认的 7 天。
        assertEquals(1, count());
    }

    @Test
    void keepsEverythingWhenSwitchedOff() {
        properties.setEnabled(false);
        insert(5, LocalDateTime.now().minusDays(30));

        scheduler.purgeExpired();

        assertEquals(5, count());
    }

    @Test
    void drainsBacklogAcrossMultipleBatches() {
        // 单批下限是 100 行（见 purgeExpired 里的 Math.max），250 行至少要走三轮。
        insert(250, LocalDateTime.now().minusDays(30));

        scheduler.purgeExpired();

        assertEquals(0, count());
    }

    @Test
    void runsEveryHourAtMinuteFive() throws NoSuchMethodException {
        Scheduled scheduled = RequestLogCleanupScheduler.class
                .getMethod("purgeExpired")
                .getAnnotation(Scheduled.class);

        CronExpression expression = CronExpression.parse(scheduled.cron());

        // 保留期改成 7 天后，清理频率就是保留精度的上限：每天一次意味着数据最多多留 24 小时。
        assertEquals(LocalDateTime.of(2026, 11, 10, 19, 5),
                expression.next(LocalDateTime.of(2026, 11, 10, 18, 20)));
    }

    private void insert(int rows, LocalDateTime requestTime) {
        for (int i = 0; i < rows; i++) {
            jdbc.update("INSERT INTO request_logs (request_time, route) VALUES (?, ?)",
                    Timestamp.valueOf(requestTime), "/api/test");
        }
    }

    private int count() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM request_logs", Integer.class);
        return total == null ? 0 : total;
    }
}

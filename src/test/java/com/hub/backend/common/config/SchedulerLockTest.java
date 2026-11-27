package com.hub.backend.common.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务互斥真的生效，而不是「加了个注解看起来像生效」。
 *
 * <p>用 {@code lockAtLeastFor=30s} 造出确定的窗口：锁在 30 秒内不会释放，所以第二次调用必须被
 * 跳过。这比并发两个线程去抢更稳定 —— 抢锁的赢家不确定，这里不需要赢家，只需要「同一时刻
 * 只有一个在跑」这个结论。</p>
 *
 * <p>顺带验证 {@code shedlock} 表真的被写上：如果 annotation 没被代理拦截，表里不会有行，
 * 两次调用也都会执行 —— 两个断言一起才能说明它确实在起锁。</p>
 */
@SpringBootTest
class SchedulerLockTest {

    @TestConfiguration
    static class LockedTaskConfig {
        @Bean
        LockedTask lockedTask() {
            return new LockedTask();
        }
    }

    /** 被锁保护的方法必须是非 final 的，否则 CGLIB 代理不掉。 */
    static class LockedTask {

        private final AtomicInteger literalRuns = new AtomicInteger();
        private final AtomicInteger placeholderRuns = new AtomicInteger();

        @SchedulerLock(name = "test-scheduler-lock", lockAtMostFor = "1m", lockAtLeastFor = "30s")
        public void run() {
            literalRuns.incrementAndGet();
        }

        /**
         * 与生产代码同样的写法：时长来自配置占位符。占位符没被解析时 ShedLock 会把它当非法时长，
         * 调用直接抛异常 —— 所以这条断言同时验证了「占位符支持」这件事。
         */
        @SchedulerLock(name = "test-scheduler-lock-placeholder",
                lockAtMostFor = "${app.video.poll-lock-at-most:15m}",
                lockAtLeastFor = "30s")
        public void runWithPlaceholder() {
            placeholderRuns.incrementAndGet();
        }

        int literalRuns() {
            return literalRuns.get();
        }

        int placeholderRuns() {
            return placeholderRuns.get();
        }
    }

    @Autowired
    private LockedTask lockedTask;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theSecondRunIsSkippedWhileTheLockIsStillHeld() {
        lockedTask.run();
        lockedTask.run();

        assertThat(lockedTask.literalRuns()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from shedlock where name = 'test-scheduler-lock'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void durationsCanComeFromConfigurationPlaceholders() {
        lockedTask.runWithPlaceholder();
        lockedTask.runWithPlaceholder();

        assertThat(lockedTask.placeholderRuns()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from shedlock where name = 'test-scheduler-lock-placeholder'",
                Integer.class)).isEqualTo(1);
    }
}

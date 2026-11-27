package com.hub.backend.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 定时任务的多实例互斥。
 *
 * <p>{@code @Scheduled} 的语义是「每个进程各跑一次」：单实例时看起来没问题，一旦起第二个副本，
 * 目录同步会各拉一遍全量快照、视频轮询会各打一遍上游状态接口。加上 {@code @SchedulerLock}
 * 之后，只有抢到锁的那一个实例执行，其余的直接跳过这一轮。</p>
 *
 * <p>{@code defaultLockAtMostFor} 只是兜底：真正卡住时锁会在这么久之后自动释放，避免一次
 * 进程被 kill 让任务永久停摆。单个任务通常会用注解上的值覆盖它，取「比正常执行时间长一截、
 * 又短到不需要人工介入」的区间。</p>
 *
 * <p>{@code usingDbTime()} 是刻意的：租约比较用数据库时间而不是各台机器的时钟。容器/NAS 的
 * 时钟漂移几十秒很常见，用本机时间会让「锁还没到期」和「锁已到期」在两台机器上得出不同结论，
 * 结果就是两个实例同时跑。</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulingLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}

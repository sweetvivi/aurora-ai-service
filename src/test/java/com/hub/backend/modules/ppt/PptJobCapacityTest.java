package com.hub.backend.modules.ppt;

import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.modules.ppt.entity.PptJobEntity;
import com.hub.backend.modules.ppt.repository.PptJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 准入额度不能在并发建单时超卖。
 *
 * <p>额度 = 并发 + 排队，而这两个属性都会被 setter 夹到最小 1，所以最小的额度是 2 ——
 * 测试按 2 行占满。</p>
 *
 * <p>关键是**被拒的时机**：如果锁只覆盖「查一次」，第二个请求会在第一个事务提交前就查完，
 * 看到 0 行、放行，于是额外出库。所以这里断言它确实等到了对方提交 —— 用耗时下限验证，
 * 而不是只看最终行数：单线程顺序执行同样只有两行，那样测不出任何东西。</p>
 */
@SpringBootTest(properties = {
        "app.ppt.ppt-master.job-concurrency=1",
        "app.ppt.ppt-master.job-queue-size=0"
})
class PptJobCapacityTest {

    /** 属性最小都是 1，所以额度固定为 2。 */
    private static final int CAPACITY = 2;

    @Autowired
    private PptJobCapacity capacity;

    @Autowired
    private PptJobRepository jobs;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void cleanUp() {
        jobs.deleteAll();
    }

    @Test
    void rejectsAsSoonAsEverySlotIsInUse() {
        for (int index = 0; index < CAPACITY; index++) {
            jobs.saveAndFlush(new PptJobEntity("ppt_job_cap_full_" + index, 1L, "占满额度"));
        }

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(
                status -> capacity.withAdmission(() -> "created")))
                .isInstanceOf(TooManyRequestsException.class);

        assertThat(jobs.count()).isEqualTo(CAPACITY);
    }

    @Test
    void theNextCallerWaitsForTheRunningTransactionToCommit() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        Throwable[] firstFailure = new Throwable[1];

        Thread first = new Thread(() -> {
            try {
                new TransactionTemplate(txManager).executeWithoutResult(status -> capacity.withAdmission(() -> {
                    for (int index = 0; index < CAPACITY; index++) {
                        jobs.saveAndFlush(new PptJobEntity("ppt_job_cap_race_" + index, 1L, "第一个建单"));
                    }
                    inserted.countDown();
                    // 插入后到提交之间停留一会儿：这段时间里第二个请求必须进不来。
                    sleep(400L);
                    return "created";
                }));
            } catch (Throwable failure) {
                firstFailure[0] = failure;
            }
        }, "ppt-admission-holder");
        first.start();

        assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(
                status -> capacity.withAdmission(() -> "created")))
                .isInstanceOf(TooManyRequestsException.class);
        long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        first.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(firstFailure[0]).isNull();
        // 必须真实地等过对方提交（400ms 的停留），否则说明锁没覆盖到提交。
        assertThat(waitedMillis)
                .as("second caller should have blocked until the first transaction committed")
                .isGreaterThanOrEqualTo(300L);
        assertThat(jobs.count()).isEqualTo(CAPACITY);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

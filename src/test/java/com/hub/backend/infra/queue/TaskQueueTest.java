package com.hub.backend.infra.queue;

import com.hub.backend.infra.queue.entity.TaskQueueEntryEntity;
import com.hub.backend.infra.queue.repository.TaskQueueEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 队列的数据库语义：领取的唯一性、重试退避、租约回收。
 *
 * <p>这些全是带条件的 UPDATE，写错条件不会编译失败，只会在并发下悄悄让两个实例跑同一条消息，
 * 或者让一条消息永远卡在 claimed。测试逐个钉住。</p>
 */
@SpringBootTest
@Transactional
class TaskQueueTest {

    private static final String QUEUE = "test.queue.db";

    @Autowired
    private TaskQueue queue;

    @Autowired
    private TaskQueueEntryRepository entries;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void publishingMakesTheTaskClaimableImmediately() {
        queue.publish(TaskMessage.of(QUEUE, "payload-1"));

        List<ClaimedTask> claimed = queue.claim(QUEUE, "worker-a", 10);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).payload()).isEqualTo("payload-1");
        // 领取即计一次尝试：领了没 ack 就死的任务也算用掉了额度，否则会无限重投。
        assertThat(claimed.get(0).attempts()).isEqualTo(1);
        assertThat(claimed.get(0).leaseOwner()).isEqualTo("worker-a");
    }

    /**
     * 刚发布的任务必须立刻可领，哪怕发布与领取发生在同一个微秒里。
     *
     * <p>这条是回归测试：列是 {@code DATETIME(6)}，写进去的纳秒会被四舍五入到微秒，而查询参数
     * 用原值比较 —— 末尾 500–900 纳秒的值一进位，刚插入的行就比 {@code now} 「未来」了 1 微秒，
     * 于是这次领不到、要等下一轮扫描。单次测试很难撞上，所以连做 200 轮压出来。</p>
     */
    @Test
    void aTaskJustPublishedIsClaimableImmediately() {
        for (int index = 0; index < 200; index++) {
            queue.publish(TaskMessage.of(QUEUE, "tight-" + index));

            assertThat(queue.claim(QUEUE, "worker-a", 1))
                    .as("iteration %d must claim the row it just published", index)
                    .hasSize(1);
        }
    }

    @Test
    void onlyOneWorkerGetsTheSameTask() {
        queue.publish(TaskMessage.of(QUEUE, "once"));

        assertThat(queue.claim(QUEUE, "worker-a", 10)).hasSize(1);
        assertThat(queue.claim(QUEUE, "worker-b", 10)).isEmpty();
    }

    @Test
    void aDelayedTaskIsNotClaimableYet() {
        queue.publish(TaskMessage.of(QUEUE, "later").availableAt(LocalDateTime.now().plusMinutes(5)));

        assertThat(queue.claim(QUEUE, "worker-a", 10)).isEmpty();
    }

    @Test
    void completingMarksTheTaskDoneAndDropsTheLease() {
        queue.publish(TaskMessage.of(QUEUE, "done"));
        ClaimedTask task = queue.claim(QUEUE, "worker-a", 10).get(0);

        queue.complete(task);

        TaskQueueEntryEntity row = entries.findById(task.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaskQueueEntryEntity.STATUS_DONE);
        assertThat(row.getLeaseOwner()).isNull();
        assertThat(row.getLeaseUntil()).isNull();
        // 已完成的不会再被领到
        assertThat(queue.claim(QUEUE, "worker-b", 10)).isEmpty();
    }

    @Test
    void aRetryableFailureGoesBackToTheQueueAfterABackoff() {
        queue.publish(TaskMessage.of(QUEUE, "flaky"));
        ClaimedTask task = queue.claim(QUEUE, "worker-a", 10).get(0);

        queue.fail(task, "storage hiccup", true);

        TaskQueueEntryEntity row = entries.findById(task.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaskQueueEntryEntity.STATUS_PENDING);
        assertThat(row.getLastError()).isEqualTo("storage hiccup");
        // 退避写在 available_at 上，而不是 sleep 线程：这一轮不该再被领到。
        assertThat(row.getAvailableAt()).isAfter(LocalDateTime.now());
        assertThat(queue.claim(QUEUE, "worker-b", 10)).isEmpty();
    }

    @Test
    void aFailureThatExhaustsTheAttemptsIsMarkedFailed() {
        queue.publish(TaskMessage.of(QUEUE, "doomed").withMaxAttempts(1));
        ClaimedTask task = queue.claim(QUEUE, "worker-a", 10).get(0);

        queue.fail(task, "permanent", true);

        TaskQueueEntryEntity row = entries.findById(task.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaskQueueEntryEntity.STATUS_FAILED);
        assertThat(row.getLastError()).isEqualTo("permanent");
    }

    @Test
    void aNonRetryableFailureIsFailedEvenWithAttemptsLeft() {
        queue.publish(TaskMessage.of(QUEUE, "bad-input").withMaxAttempts(5));
        ClaimedTask task = queue.claim(QUEUE, "worker-a", 10).get(0);

        queue.fail(task, "cannot ever succeed", false);

        assertThat(entries.findById(task.id()).orElseThrow().getStatus())
                .isEqualTo(TaskQueueEntryEntity.STATUS_FAILED);
    }

    @Test
    void releasingPutsTheTaskBackWithoutConsumingTheBackoff() {
        queue.publish(TaskMessage.of(QUEUE, "returned"));
        ClaimedTask task = queue.claim(QUEUE, "worker-a", 10).get(0);

        queue.release(task);

        TaskQueueEntryEntity row = entries.findById(task.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaskQueueEntryEntity.STATUS_PENDING);
        assertThat(row.getLeaseOwner()).isNull();
        // 立即可领：线程池满时归还的消息不该再等一个退避周期；而且这次没跑过，尝试次数要还回去。
        List<ClaimedTask> again = queue.claim(QUEUE, "worker-b", 10);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).attempts()).isEqualTo(1);
    }

    @Test
    void anExpiredLeaseIsReclaimedForTheNextWorker() {
        queue.publish(TaskMessage.of(QUEUE, "abandoned"));
        ClaimedTask first = queue.claim(QUEUE, "worker-a", 10).get(0);
        // 模拟执行者卡死：租约到期，但状态还是 claimed。
        jdbc.update("update task_queue set lease_until = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), first.id());

        assertThat(queue.releaseExpired()).isEqualTo(1);

        List<ClaimedTask> again = queue.claim(QUEUE, "worker-b", 10);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).attempts()).isEqualTo(2);
    }

    @Test
    void anExpiredLeaseWithNoAttemptsLeftIsMarkedFailed() {
        queue.publish(TaskMessage.of(QUEUE, "never-again").withMaxAttempts(1));
        ClaimedTask task = queue.claim(QUEUE, "worker-a", 10).get(0);
        jdbc.update("update task_queue set lease_until = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), task.id());

        queue.releaseExpired();

        TaskQueueEntryEntity row = entries.findById(task.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaskQueueEntryEntity.STATUS_FAILED);
        assertThat(row.getLastError()).contains("租约过期");
    }

    @Test
    void aWorkerCannotAckATaskItNoLongerOwns() {
        queue.publish(TaskMessage.of(QUEUE, "stolen"));
        ClaimedTask mine = queue.claim(QUEUE, "worker-a", 10).get(0);
        // 别的实例在租约过期后接管
        jdbc.update("update task_queue set status = 'claimed', lease_owner = 'worker-b' where id = ?", mine.id());

        queue.complete(mine);

        assertThat(entries.findById(mine.id()).orElseThrow().getStatus())
                .isEqualTo(TaskQueueEntryEntity.STATUS_CLAIMED);
    }
}

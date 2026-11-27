package com.hub.backend.infra.queue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发件箱语义：消息必须和业务写在同一个事务里。
 *
 * <p>这是整个队列最关键的一条 —— 如果 publish 自己开了新事务（或者被推迟到 afterCommit），
 * 业务回滚之后消息还在，消费者就可能去删一个「还在用的文件」。反过来，业务提交了消息却丢了，
 * 就退回到原来的问题。</p>
 *
 * <p>刻意不加 {@code @Transactional}：本类要观察真实的提交/回滚，事务由 {@link TransactionTemplate}
 * 显式控制。同时这里混用 JPA（publish）与 JdbcTemplate（校验），顺带证明两者共享同一个连接。</p>
 */
@SpringBootTest
class TaskOutboxTest {

    private static final String QUEUE = "test.outbox";

    @Autowired
    private TaskQueue queue;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aPublishedTaskIsVisibleInsideTheTransactionThatPublishedIt() {
        TransactionTemplate template = new TransactionTemplate(txManager);

        template.executeWithoutResult(status -> {
            queue.publish(TaskMessage.of(QUEUE, "in-flight"));

            assertThat(count(QUEUE)).isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    @Test
    void rollingBackTheBusinessTransactionDropsTheTask() {
        TransactionTemplate template = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            queue.publish(TaskMessage.of(QUEUE, "rolled-back"));
            throw new IllegalStateException("业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count(QUEUE)).isZero();
    }

    @Test
    void committingTheBusinessTransactionKeepsTheTask() {
        TransactionTemplate template = new TransactionTemplate(txManager);

        template.executeWithoutResult(status -> queue.publish(TaskMessage.of(QUEUE + ".commit", "kept")));

        try {
            assertThat(count(QUEUE + ".commit")).isEqualTo(1);
        } finally {
            jdbc.update("delete from task_queue where queue_name = ?", QUEUE + ".commit");
        }
    }

    private int count(String queueName) {
        Integer count = jdbc.queryForObject(
                "select count(*) from task_queue where queue_name = ?", Integer.class, queueName);
        return count == null ? 0 : count;
    }
}

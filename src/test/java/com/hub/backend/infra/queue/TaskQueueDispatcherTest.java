package com.hub.backend.infra.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派发链路：领到消息 → 交给处理器 → ack / nack。
 *
 * <p>处理器跑在线程池里，所以断言要等；这里用「轮询直到超时」而不是固定 sleep，避免机器快慢影响结果。</p>
 */
@SpringBootTest
class TaskQueueDispatcherTest {

    private static final long TIMEOUT_MILLIS = 5000;

    @TestConfiguration
    static class HandlerConfig {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        FailingHandler failingHandler() {
            return new FailingHandler();
        }
    }

    static class RecordingHandler implements TaskHandler {
        static final String QUEUE = "test.dispatch.ok";
        final List<String> handled = new CopyOnWriteArrayList<>();

        @Override
        public String queueName() {
            return QUEUE;
        }

        @Override
        public void handle(String payload) {
            handled.add(payload);
        }
    }

    static class FailingHandler implements TaskHandler {
        static final String QUEUE = "test.dispatch.fail";

        @Override
        public String queueName() {
            return QUEUE;
        }

        @Override
        public void handle(String payload) {
            throw new IllegalStateException("storage down");
        }
    }

    @Autowired
    private TaskQueueDispatcher dispatcher;

    @Autowired
    private TaskQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingHandler recording;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from task_queue where queue_name in (?, ?)",
                RecordingHandler.QUEUE, FailingHandler.QUEUE);
        // 处理器是单例 bean，记录列表跨用例共享；不清掉的话上一个用例的 payload 会算进这一个。
        recording.handled.clear();
    }

    @Test
    void handsTheTaskToItsHandlerAndMarksItDone() {
        queue.publish(TaskMessage.of(RecordingHandler.QUEUE, "hello"));

        dispatcher.runOnce();

        await(() -> "done".equals(statusOf(RecordingHandler.QUEUE, "hello")));
        assertThat(recording.handled).contains("hello");
    }

    /**
     * 一轮要把队列见底，而不是只领一批。
     *
     * <p>只领一批时吞吐被钉在 {@code batch-size / poll-interval}（默认 10 条 / 2 秒 = 5 条/秒）。
     * 25 条任务、批大小 10：旧实现一轮只做 10 条，剩下的要等两秒后的下一轮。</p>
     */
    @Test
    void oneSweepDrainsSeveralBatches() {
        for (int index = 0; index < 25; index++) {
            queue.publish(TaskMessage.of(RecordingHandler.QUEUE, "batch-" + index));
        }

        dispatcher.runOnce();

        await(() -> doneCount(RecordingHandler.QUEUE) == 25);
        assertThat(recording.handled).hasSize(25);
    }

    @Test
    void aFailingHandlerPutsTheTaskBackForARetry() {
        queue.publish(TaskMessage.of(FailingHandler.QUEUE, "boom").withMaxAttempts(3));

        dispatcher.runOnce();

        await(() -> {
            String error = jdbc.query(
                    "select last_error from task_queue where queue_name = ? and payload = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    FailingHandler.QUEUE, "boom");
            return error != null && error.contains("storage down");
        });
        // 还在重试窗口内：状态回到 pending，等退避结束再被领取。
        assertThat(statusOf(FailingHandler.QUEUE, "boom")).isEqualTo("pending");
        Integer attempts = jdbc.queryForObject(
                "select attempts from task_queue where queue_name = ? and payload = ?",
                Integer.class, FailingHandler.QUEUE, "boom");
        assertThat(attempts).isEqualTo(1);
    }

    private String statusOf(String queueName, String payload) {
        return jdbc.query("select status from task_queue where queue_name = ? and payload = ?",
                rs -> rs.next() ? rs.getString(1) : null, queueName, payload);
    }

    private int doneCount(String queueName) {
        Integer count = jdbc.queryForObject(
                "select count(*) from task_queue where queue_name = ? and status = 'done'",
                Integer.class, queueName);
        return count == null ? 0 : count;
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition not met within " + TIMEOUT_MILLIS + " ms");
    }
}

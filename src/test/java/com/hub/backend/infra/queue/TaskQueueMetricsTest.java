package com.hub.backend.infra.queue;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 队列指标：积压深度与最老待办年龄。
 *
 * <p>关掉派发器（{@code app.queue.enabled=false}），否则任务会被真的领走，`pending` 归零，
 * 断言就成了测运气。</p>
 */
@SpringBootTest(properties = "app.queue.enabled=false")
class TaskQueueMetricsTest {

    private static final String QUEUE = "test.metrics";

    @Autowired
    private TaskQueue queue;

    @Autowired
    private TaskQueueMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from task_queue where queue_name = ?", QUEUE);
    }

    @Test
    void reportsBacklogDepthAndHowLongTheOldestHasWaited() {
        for (int index = 0; index < 3; index++) {
            queue.publish(TaskMessage.of(QUEUE, "m" + index));
        }
        // 把最老那条推到一小时前创建，模拟积压。
        jdbc.update("update task_queue set created_at = ? where queue_name = ? and payload = 'm0'",
                LocalDateTime.now().minusHours(1), QUEUE);

        metrics.refresh();

        assertThat(gauge(TaskQueueMetrics.PENDING)).isEqualTo(3.0);
        // 年龄按秒截断，写库与读取之间还有毫秒级延迟，所以给它一分钟的余量而不是卡 3600。
        assertThat(gauge(TaskQueueMetrics.OLDEST_PENDING_AGE)).isGreaterThanOrEqualTo(3500.0);
        assertThat(gauge(TaskQueueMetrics.CLAIMED)).isZero();
    }

    @Test
    void anEmptiedQueueReportsZeroInsteadOfKeepingTheOldValue() {
        queue.publish(TaskMessage.of(QUEUE, "solo"));
        metrics.refresh();
        assertThat(gauge(TaskQueueMetrics.PENDING)).isEqualTo(1.0);

        // 消息被处理掉：指标必须跟着归零，否则曲线会永远停在旧值上。
        jdbc.update("update task_queue set status = 'done' where queue_name = ?", QUEUE);
        metrics.refresh();

        assertThat(gauge(TaskQueueMetrics.PENDING)).isZero();
        assertThat(gauge(TaskQueueMetrics.OLDEST_PENDING_AGE)).isZero();
    }

    private double gauge(String name) {
        return registry.get(name).tag("queue", QUEUE).gauge().value();
    }
}

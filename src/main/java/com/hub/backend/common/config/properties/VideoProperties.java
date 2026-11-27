package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Scheduling policy for asynchronous video generation jobs.
 *
 * <p>Bound from {@code app.video.*}. Video is not a request/response call: the upstream answers
 * with a task id and the result has to be polled, so these values govern the poller rather than a
 * single HTTP request timeout.</p>
 */
@ConfigurationProperties(prefix = "app.video")
public class VideoProperties {

    /** How often a running task is re-checked upstream. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /**
     * Wall clock limit of one generation. A task still running after this is marked failed, so a
     * lost upstream task cannot keep a poll cycle alive forever.
     */
    private Duration taskTimeout = Duration.ofMinutes(25);

    /** How many unfinished generations one account may have at the same time. */
    private int maxConcurrentPerUser = 2;

    /** How many tasks one poll cycle handles; keeps a backlog from blocking the scheduler thread. */
    private int pollBatchSize = 50;

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getTaskTimeout() {
        return taskTimeout;
    }

    public void setTaskTimeout(Duration taskTimeout) {
        this.taskTimeout = taskTimeout;
    }

    public int getMaxConcurrentPerUser() {
        return maxConcurrentPerUser;
    }

    public void setMaxConcurrentPerUser(int maxConcurrentPerUser) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
    }

    public int getPollBatchSize() {
        return pollBatchSize;
    }

    public void setPollBatchSize(int pollBatchSize) {
        this.pollBatchSize = pollBatchSize;
    }
}

package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 通用任务队列（{@code app.queue}）。
 *
 * <p>默认值按「单实例、副作用型任务（删文件、发通知、补偿计费）」定：轮询 2 秒、租约 10 分钟、
 * 退避 30 秒起、最多 5 次。处理器如果会跑很久（分钟级上游调用），要么调大租约，要么别放进这个
 * 队列 —— 这里的租约不会自动续期。</p>
 */
@ConfigurationProperties(prefix = "app.queue")
public class QueueProperties {

    /** 总开关。关掉之后不再轮询，已投递的消息留在库里等重新打开。 */
    private boolean enabled = true;

    /** 两轮领取之间的间隔。 */
    private Duration pollInterval = Duration.ofSeconds(2);

    /** 启动后多久开始第一轮：让 Flyway 与连接池先就绪。 */
    private Duration initialDelay = Duration.ofSeconds(10);

    /** 单个队列每轮最多领多少条。 */
    private int batchSize = 10;

    /**
     * 单轮最多连续领几批。
     *
     * <p>只领一批的话，吞吐被钉死在 {@code batch-size / poll-interval}：默认就是 10 条 / 2 秒 =
     * **5 条/秒**，处理器再快也没用。平时每小时几条无所谓，但 Redis/库抖动后积压一批任务时，
     * 1000 条要排三分多钟。连续领几批把追赶能力提上来，同时用轮数封顶，避免一轮扫太久把调度线程占住。</p>
     *
     * <p>真正的上限仍是处理器线程池：池满时提交被拒，本轮立即停止领取（见 TaskQueueDispatcher）。</p>
     */
    private int maxBatchesPerSweep = 5;

    /** 租约时长。必须大于处理器的单次执行时间，队列不会替处理器续租。 */
    private Duration leaseTtl = Duration.ofMinutes(10);

    /** 默认最大尝试次数（消息没显式指定时用）。 */
    private int maxAttempts = 5;

    /** 退避起点：第 1 次失败后等这么久，之后每次翻倍。 */
    private Duration retryBaseDelay = Duration.ofSeconds(30);

    /** 退避上限。 */
    private Duration retryMaxDelay = Duration.ofMinutes(30);

    /** 执行处理器的线程数。 */
    private int workerThreads = 2;

    /** 处理器等待队列长度；满了就把刚领到的任务还回去，等下一轮。 */
    private int workerQueueSize = 64;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerSweep() {
        return maxBatchesPerSweep;
    }

    public void setMaxBatchesPerSweep(int maxBatchesPerSweep) {
        this.maxBatchesPerSweep = maxBatchesPerSweep;
    }

    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    public void setLeaseTtl(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getWorkerQueueSize() {
        return workerQueueSize;
    }

    public void setWorkerQueueSize(int workerQueueSize) {
        this.workerQueueSize = workerQueueSize;
    }
}

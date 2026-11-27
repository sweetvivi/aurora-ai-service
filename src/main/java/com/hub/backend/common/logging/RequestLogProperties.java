package com.hub.backend.common.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求日志配置，绑定自 {@code app.request-log.*}。
 *
 * <p>默认全开：日志缺失导致的事故复盘成本，远高于这点写入开销。开关留在这里是为了
 * 压测或线上故障时能立刻关掉而不必发版。</p>
 */
@ConfigurationProperties(prefix = "app.request-log")
public class RequestLogProperties {

    /** 总开关。关闭后过滤器直接放行，不产生任何 ThreadLocal 与队列开销。 */
    private boolean enabled = true;

    /** 请求体落库前的最大字符数。超过部分截断并标注，避免大报文撑爆 TEXT 列。 */
    private int maxBodyLength = 8192;

    /** 待写队列容量。满了直接丢弃并计数——宁可丢日志，也不能让接口被日志拖慢。 */
    private int queueCapacity = 8192;

    /** 单批最多写入行数。50~200 之间对 MySQL 都比较友好。 */
    private int batchSize = 100;

    /** 队列为空时的最长等待时间，决定日志写入延迟的上限。 */
    private int flushIntervalMs = 500;

    /**
     * 保留天数，超期由清理任务物理删除。
     *
     * <p>7 天：访问日志的用途是「最近几天出了什么问题」，超过一周的请求明细基本只在事故复盘时
     * 才翻，而它是一张只增不减的大表。真正需要长期留存的「谁把余额改成了多少」在审计表里
     * （{@code audit_logs}，默认 180 天），两者不要混为一谈。</p>
     */
    private int retentionDays = 7;

    /** 清理任务每批删除行数，避免一次性 DELETE 造成长事务与主从延迟。 */
    private int purgeBatchSize = 5000;

    /** 不记录的路径前缀，默认放行健康检查与静态资源。 */
    private List<String> skipPaths = new ArrayList<>(List.of("/actuator", "/error", "/favicon.ico"));

    /** 请求体不落库的媒体类型。默认只记录 JSON，其余（表单、二进制、事件流）一律跳过。 */
    private List<String> bodyContentTypes = new ArrayList<>(List.of("application/json"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBodyLength() {
        return maxBodyLength;
    }

    public void setMaxBodyLength(int maxBodyLength) {
        this.maxBodyLength = maxBodyLength;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(int flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getPurgeBatchSize() {
        return purgeBatchSize;
    }

    public void setPurgeBatchSize(int purgeBatchSize) {
        this.purgeBatchSize = purgeBatchSize;
    }

    public List<String> getSkipPaths() {
        return skipPaths;
    }

    public void setSkipPaths(List<String> skipPaths) {
        this.skipPaths = skipPaths;
    }

    public List<String> getBodyContentTypes() {
        return bodyContentTypes;
    }

    public void setBodyContentTypes(List<String> bodyContentTypes) {
        this.bodyContentTypes = bodyContentTypes;
    }
}

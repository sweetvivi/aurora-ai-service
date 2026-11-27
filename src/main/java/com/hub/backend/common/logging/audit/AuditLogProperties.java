package com.hub.backend.common.logging.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计日志配置，绑定自 {@code app.audit-log.*}。
 *
 * <p>默认开启，且**不建议在生产关闭**：访问日志丢了只是排查变难，审计日志丢了是合规问题——
 * 谁在什么时候把余额改成了多少、把哪个模型下架了，事后必须有据可查。</p>
 */
@ConfigurationProperties(prefix = "app.audit-log")
public class AuditLogProperties {

    /** 总开关。关闭后切面直接放行，不产生快照与队列开销（仅用于压测）。 */
    private boolean enabled = true;

    /** before/after 快照落库前的最大字符数。批量操作可能一次改上百行，超长只留前 N 字符。 */
    private int maxSnapshotLength = 8192;

    /** 待写队列容量。满了丢弃并记数，绝不让审计拖慢业务线程。 */
    private int queueCapacity = 4096;

    /** 单批写入行数。 */
    private int batchSize = 50;

    /** 队列为空时的最长等待时间。审计不是实时看板，500ms 延迟完全够用。 */
    private int flushIntervalMs = 500;

    /** 保留天数。比访问日志长得多：财务与合规追溯通常按季度甚至按年。 */
    private int retentionDays = 180;

    /** 清理任务每批删除行数，避免一次性 DELETE 造成长事务。 */
    private int purgeBatchSize = 2000;

    /** 启动时扫描「管理端写接口但没加 @AuditLog」并打 WARN，防止新接口漏加。 */
    private boolean warnMissingAnnotations = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSnapshotLength() {
        return maxSnapshotLength;
    }

    public void setMaxSnapshotLength(int maxSnapshotLength) {
        this.maxSnapshotLength = maxSnapshotLength;
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

    public boolean isWarnMissingAnnotations() {
        return warnMissingAnnotations;
    }

    public void setWarnMissingAnnotations(boolean warnMissingAnnotations) {
        this.warnMissingAnnotations = warnMissingAnnotations;
    }
}

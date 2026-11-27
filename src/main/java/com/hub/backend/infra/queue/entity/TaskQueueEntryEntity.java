package com.hub.backend.infra.queue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * {@code task_queue} 的一行。
 *
 * <p>实体放在 infra 是有意的：它是队列适配器自己的存储，不属于任何业务模块。业务只认
 * {@link com.hub.backend.infra.queue.TaskQueue}，换成 RabbitMQ 时这张表和这个类一起消失。</p>
 *
 * <p>它同时承担另一个职责：H2 测试库由 Hibernate 按实体建表，没有实体就没有 `task_queue`，
 * 队列相关的测试跑不起来。</p>
 *
 * <p>注意所有状态推进都走仓储里的**条件 UPDATE**，不读实体改字段再 save：领取、收尾、重投
 * 都是并发路径，整行写回会互相覆盖（{@code PptJobRepository} 里记录过同样的问题）。</p>
 */
@Entity
@Table(name = "task_queue")
public class TaskQueueEntryEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CLAIMED = "claimed";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "queue_name", nullable = false, length = 64)
    private String queueName;

    /** 调用方自定的不透明内容，本项目约定为 JSON。列类型必须显式声明，否则 validate 会拒绝启动。 */
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "last_error", length = 2048)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TaskQueueEntryEntity() {
    }

    public TaskQueueEntryEntity(String queueName, String payload, LocalDateTime availableAt, int maxAttempts) {
        this.queueName = queueName;
        this.payload = payload;
        this.availableAt = availableAt;
        this.maxAttempts = maxAttempts;
    }

    public Long getId() {
        return id;
    }

    public String getQueueName() {
        return queueName;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

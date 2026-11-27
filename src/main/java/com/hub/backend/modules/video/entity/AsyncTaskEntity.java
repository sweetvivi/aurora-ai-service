package com.hub.backend.modules.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One asynchronous generation job, stored in the shared {@code async_tasks} table.
 *
 * <p>The table predates this module (V1) and is generic on purpose: {@link #taskType} separates
 * video generation from whatever else needs a long running job. Reusing it means the poller, the
 * history endpoint and the "what is this account running" check all read one place instead of a
 * video-only table that would have to duplicate them.</p>
 *
 * <p>{@code input_json} keeps the request that produced the job (prompt, size, model) and
 * {@code result_json} the outcome ({@code videoUrl}, {@code fileId}, {@code mimeType}). Neither is
 * mapped by JPA on purpose — see {@code docs/database-schema.md} on JSON columns: they are written
 * as strings so the schema stays the source of truth.</p>
 */
@Entity
@Table(name = "async_tasks")
public class AsyncTaskEntity {

    public static final String TYPE_VIDEO_GENERATION = "VIDEO_GENERATION";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_type", nullable = false, length = 64)
    private String taskType;

    /** Public task identifier. The numeric id is never exposed: it would leak row counts. */
    @Column(name = "task_key", unique = true, length = 64)
    private String taskKey;

    @Column(name = "provider_task_id", length = 255)
    private String providerTaskId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_PENDING;

    @Column(name = "progress", nullable = false, precision = 5, scale = 2)
    private BigDecimal progress = BigDecimal.ZERO;

    @Column(name = "input_json", columnDefinition = "json")
    private String inputJson;

    @Column(name = "result_json", columnDefinition = "json")
    private String resultJson;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AsyncTaskEntity() {
        // for JPA
    }

    public AsyncTaskEntity(Long userId, String taskType, String taskKey, String inputJson) {
        this.userId = userId;
        this.taskType = taskType;
        this.taskKey = taskKey;
        this.inputJson = inputJson;
        this.status = STATUS_PENDING;
        this.progress = BigDecimal.ZERO;
    }

    public boolean isTerminal() {
        return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    public void markRunning(String providerTaskId, LocalDateTime now) {
        this.status = STATUS_RUNNING;
        this.providerTaskId = providerTaskId;
        this.startedAt = now;
    }

    public void markProgress(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        this.progress = BigDecimal.valueOf(clamped);
    }

    public void markSucceeded(String resultJson, LocalDateTime now) {
        this.status = STATUS_SUCCEEDED;
        this.progress = BigDecimal.valueOf(100);
        this.resultJson = resultJson;
        this.completedAt = now;
    }

    public void markFailed(String reason, LocalDateTime now) {
        this.status = STATUS_FAILED;
        this.errorMessage = truncate(reason);
        this.completedAt = now;
    }

    public void markCancelled(LocalDateTime now) {
        this.status = STATUS_CANCELLED;
        this.completedAt = now;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public String getProviderTaskId() {
        return providerTaskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getProgress() {
        return progress;
    }

    public String getInputJson() {
        return inputJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

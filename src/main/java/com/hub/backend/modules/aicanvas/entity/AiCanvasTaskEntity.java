package com.hub.backend.modules.aicanvas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_canvas_tasks")
public class AiCanvasTaskEntity {

    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_LOCAL = "LOCAL_MODEL";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    @Column(name = "provider_task_id", length = 255)
    private String providerTaskId;

    @Column(name = "model_key", length = 128)
    private String modelKey;

    @Column(name = "upstream_model", length = 255)
    private String upstreamModel;

    @Column(name = "prompt", columnDefinition = "LONGTEXT")
    private String prompt;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AiCanvasTaskEntity() {
    }

    public AiCanvasTaskEntity(String taskId, Long userId, String taskType, String providerTaskId,
                              String modelKey, String upstreamModel, String prompt, String status,
                              int progress, String resultJson, String errorMessage) {
        this.taskId = taskId;
        this.userId = userId;
        this.taskType = taskType;
        this.providerTaskId = providerTaskId;
        this.modelKey = modelKey;
        this.upstreamModel = upstreamModel;
        this.prompt = prompt;
        this.status = status;
        this.progress = progress;
        this.resultJson = resultJson;
        this.errorMessage = errorMessage;
    }

    public void update(String status, int progress, String resultJson, String errorMessage) {
        this.status = status;
        this.progress = Math.max(0, Math.min(progress, 100));
        this.resultJson = resultJson;
        this.errorMessage = errorMessage;
    }

    public String getTaskId() {
        return taskId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getProviderTaskId() {
        return providerTaskId;
    }

    public String getModelKey() {
        return modelKey;
    }

    public String getUpstreamModel() {
        return upstreamModel;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

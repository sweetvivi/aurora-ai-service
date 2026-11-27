package com.hub.backend.modules.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** One concrete model in a fallback group, ordered by its zero-based priority. */
@Entity
@Table(name = "llm_model_fallback_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_llm_model_fallback_members_group_model",
                columnNames = {"group_id", "model_id"}))
public class LlmModelFallbackMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmModelFallbackMemberEntity() {
    }

    public LlmModelFallbackMemberEntity(Long groupId, Long modelId, int priority) {
        this.groupId = groupId;
        this.modelId = modelId;
        this.priority = priority;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getModelId() {
        return modelId;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

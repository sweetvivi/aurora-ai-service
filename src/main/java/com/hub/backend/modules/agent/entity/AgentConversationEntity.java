package com.hub.backend.modules.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_conversations")
public class AgentConversationEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected AgentConversationEntity() {
    }

    public AgentConversationEntity(Long siteId, Long userId, Long agentId, Long modelId, String title) {
        this.siteId = siteId;
        this.userId = userId;
        this.agentId = agentId;
        this.modelId = modelId;
        this.title = title;
    }

    public boolean applyTitleIfBlank(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (title != null && !title.isBlank()) {
            return false;
        }
        title = candidate;
        return true;
    }

    public void registerMessages(int added, LocalDateTime at) {
        messageCount = Math.max(0, messageCount + added);
        lastMessageAt = at;
    }

    public void softDelete(LocalDateTime at) {
        status = STATUS_DELETED;
        deletedAt = at == null ? LocalDateTime.now() : at;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public Long getModelId() {
        return modelId;
    }

    public String getTitle() {
        return title;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public boolean isPinned() {
        return pinned;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}

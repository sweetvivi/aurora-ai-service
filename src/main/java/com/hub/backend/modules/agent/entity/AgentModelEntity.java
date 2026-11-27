package com.hub.backend.modules.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_models",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_models_agent_model",
                columnNames = {"agent_id", "model_id"}))
public class AgentModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "is_default", nullable = false)
    private boolean defaultModel;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentModelEntity() {
    }

    public AgentModelEntity(Long agentId, Long modelId, boolean defaultModel, int sortOrder) {
        this.agentId = agentId;
        this.modelId = modelId;
        this.defaultModel = defaultModel;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public Long getModelId() {
        return modelId;
    }

    public boolean isDefaultModel() {
        return defaultModel;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

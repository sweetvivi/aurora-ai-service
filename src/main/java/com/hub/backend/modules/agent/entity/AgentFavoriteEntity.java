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
@Table(name = "agent_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_favorites_user_agent",
                columnNames = {"user_id", "agent_id"}))
public class AgentFavoriteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentFavoriteEntity() {
    }

    public AgentFavoriteEntity(Long userId, Long agentId) {
        this.userId = userId;
        this.agentId = agentId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

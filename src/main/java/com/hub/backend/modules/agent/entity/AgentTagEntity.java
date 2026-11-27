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
@Table(name = "agent_tags",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_tags_agent_tag",
                columnNames = {"agent_id", "tag"}))
public class AgentTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "tag", nullable = false, length = 64)
    private String tag;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentTagEntity() {
    }

    public AgentTagEntity(Long agentId, String tag, int sortOrder) {
        this.agentId = agentId;
        this.tag = tag;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getTag() {
        return tag;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

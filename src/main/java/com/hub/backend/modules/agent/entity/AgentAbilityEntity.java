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
@Table(name = "agent_abilities",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_abilities_agent_key",
                columnNames = {"agent_id", "ability_key"}))
public class AgentAbilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "ability_key", nullable = false, length = 128)
    private String abilityKey;

    @Column(name = "ability_name", length = 128)
    private String abilityName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentAbilityEntity() {
    }

    public AgentAbilityEntity(Long agentId, String abilityKey, String abilityName, int sortOrder) {
        this.agentId = agentId;
        this.abilityKey = abilityKey;
        this.abilityName = abilityName;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getAbilityKey() {
        return abilityKey;
    }

    public String getAbilityName() {
        return abilityName;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

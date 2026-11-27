package com.hub.backend.modules.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_questions")
public class AgentQuestionEntity {

    public static final String TYPE_OPENING = "OPENING";
    public static final String TYPE_SUGGEST = "SUGGEST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "question_type", nullable = false, length = 32)
    private String questionType = TYPE_SUGGEST;

    @Column(name = "content", nullable = false, length = 512)
    private String content;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AgentQuestionEntity() {
    }

    public AgentQuestionEntity(Long agentId, String questionType, String content, int sortOrder) {
        this.agentId = agentId;
        this.questionType = questionType;
        this.content = content;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getQuestionType() {
        return questionType;
    }

    public String getContent() {
        return content;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

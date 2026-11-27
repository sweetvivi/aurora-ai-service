package com.hub.backend.modules.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_handoffs")
public class SupportHandoffEntity {

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_HANDLED = "HANDLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "contact", length = 120)
    private String contact;

    @Column(name = "page_url", length = 512)
    private String pageUrl;

    @Column(name = "agent_conversation_id")
    private Long agentConversationId;

    @Column(name = "transcript_snapshot", columnDefinition = "MEDIUMTEXT")
    private String transcriptSnapshot;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_NEW;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public Long getAgentConversationId() {
        return agentConversationId;
    }

    public void setAgentConversationId(Long agentConversationId) {
        this.agentConversationId = agentConversationId;
    }

    public String getTranscriptSnapshot() {
        return transcriptSnapshot;
    }

    public void setTranscriptSnapshot(String transcriptSnapshot) {
        this.transcriptSnapshot = transcriptSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }
}

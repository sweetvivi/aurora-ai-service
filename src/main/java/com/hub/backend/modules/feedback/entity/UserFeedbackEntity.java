package com.hub.backend.modules.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A message a visitor or an account sent from the public feedback page.
 *
 * <p>{@code userId} / {@code siteId} stay null for anonymous submissions: the page is reachable
 * without signing in, and tying feedback to an account is only useful for follow-up.
 */
@Entity
@Table(name = "user_feedback")
public class UserFeedbackEntity {

    public static final String STATUS_NEW = "NEW";
    /** 运营已经看过并处理（回复过 / 改完了 / 判定为无效）；列表页只在这两态之间切换。 */
    public static final String STATUS_HANDLED = "HANDLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "site_id")
    private Long siteId;

    /** FEATURE / BUG / BILLING / OTHER, see {@code FeedbackService}. */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "contact", length = 120)
    private String contact;

    /** Where the sender was when writing, so a report about one page can be reproduced. */
    @Column(name = "page_url", length = 512)
    private String pageUrl;

    /** FEEDBACK：意见反馈页提交。历史客服转人工已迁至 support_handoffs。 */
    @Column(name = "source", nullable = false, length = 32)
    private String source = "FEEDBACK";

    @Column(name = "agent_conversation_id")
    private Long agentConversationId;

    @Column(name = "transcript_snapshot", columnDefinition = "MEDIUMTEXT")
    private String transcriptSnapshot;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_NEW;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
}

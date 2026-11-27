package com.hub.backend.modules.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** One user's generated commerce result; the image bytes live in the file store. */
@Entity
@Table(name = "commerce_generation_history")
public class CommerceGenerationHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tool_key", nullable = false, length = 128)
    private String toolKey;

    @Column(name = "tool_label", nullable = false, length = 255)
    private String toolLabel;

    @Column(name = "prompt", length = 4000)
    private String prompt;

    @Column(name = "result_url", nullable = false, length = 1024)
    private String resultUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CommerceGenerationHistoryEntity() {
    }

    public CommerceGenerationHistoryEntity(Long siteId, Long userId, String toolKey,
                                           String toolLabel, String prompt, String resultUrl) {
        this.siteId = siteId;
        this.userId = userId;
        this.toolKey = toolKey;
        this.toolLabel = toolLabel;
        this.prompt = prompt;
        this.resultUrl = resultUrl;
    }

    public Long getId() { return id; }
    public Long getSiteId() { return siteId; }
    public Long getUserId() { return userId; }
    public String getToolKey() { return toolKey; }
    public String getToolLabel() { return toolLabel; }
    public String getPrompt() { return prompt; }
    public String getResultUrl() { return resultUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

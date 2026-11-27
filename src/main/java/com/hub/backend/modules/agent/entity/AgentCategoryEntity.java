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
@Table(name = "agent_categories")
public class AgentCategoryEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "category_key", nullable = false, length = 128)
    private String categoryKey;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AgentCategoryEntity() {
    }

    public AgentCategoryEntity(Long siteId, String categoryKey, String name) {
        this.siteId = siteId;
        this.categoryKey = categoryKey;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getCategoryKey() {
        return categoryKey;
    }

    public String getName() {
        return name;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public String getDescription() {
        return description;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

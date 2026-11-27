package com.hub.backend.modules.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Operational brand (vendor) for public model pickers.
 *
 * <p>New API still owns model rows; this table only decorates families derived from
 * {@code llm_models.company} with a stable key, icon and sort order.</p>
 */
@Entity
@Table(name = "llm_model_brands")
public class LlmModelBrandEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_key", nullable = false, length = 64)
    private String brandKey;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmModelBrandEntity() {
    }

    public LlmModelBrandEntity(String brandKey, String displayName) {
        this.brandKey = brandKey;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getBrandKey() {
        return brandKey;
    }

    public void setBrandKey(String brandKey) {
        this.brandKey = brandKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

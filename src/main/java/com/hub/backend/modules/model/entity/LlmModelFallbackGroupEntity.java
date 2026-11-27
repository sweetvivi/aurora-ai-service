package com.hub.backend.modules.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A public model-version group whose members may replace one another on failure.
 *
 * <p>This is deliberately separate from {@code llm_model_groups}: the latter describes the
 * channel a model is sold through, while this table describes interchangeable upstream
 * implementations of the same product version.</p>
 */
@Entity
@Table(name = "llm_model_fallback_groups",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_llm_model_fallback_groups_site_key",
                columnNames = {"site_id", "group_key"}))
public class LlmModelFallbackGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "group_key", nullable = false, length = 128)
    private String groupKey;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "model_type", nullable = false, length = 32)
    private String modelType;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "order_customized", nullable = false)
    private boolean orderCustomized;

    /**
     * Overrides the duration declaration the catalogue publishes for this group.
     *
     * <p>Members of one group are interchangeable but not identical: most seedance-2.0 members
     * take any 4–15 秒 while {@code fast-GT} only takes 10 or 15. Publishing the representative
     * member's declaration offers seconds that a member will refuse; publishing the intersection
     * hides legal lengths as soon as one strict member joins. An operator override is the only
     * way to state the intended offer without also removing members.</p>
     *
     * <p>{@code null} means "derive from members" — the behaviour before this column existed.</p>
     */
    @Column(name = "duration_mode", length = 16)
    private String durationMode;

    /** JSON integer array; its meaning depends on {@link #durationMode}. */
    @Column(name = "durations_json", columnDefinition = "TEXT")
    private String durationsJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmModelFallbackGroupEntity() {
    }

    public LlmModelFallbackGroupEntity(Long siteId, String groupKey, String displayName,
                                       String modelType) {
        this.siteId = siteId;
        this.groupKey = groupKey;
        this.displayName = displayName;
        this.modelType = modelType;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getModelType() {
        return modelType;
    }

    public String getStatus() {
        return status;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isOrderCustomized() {
        return orderCustomized;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setOrderCustomized(boolean orderCustomized) {
        this.orderCustomized = orderCustomized;
    }

    public String getDurationMode() {
        return durationMode;
    }

    public void setDurationMode(String durationMode) {
        this.durationMode = durationMode;
    }

    public String getDurationsJson() {
        return durationsJson;
    }

    public void setDurationsJson(String durationsJson) {
        this.durationsJson = durationsJson;
    }
}

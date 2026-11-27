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
 * A presentation group of the model catalogue, such as a provider.
 *
 * <p>The V1 {@code is_classify} and {@code is_group} flags are not mapped: nothing reads them yet,
 * and every extra boolean column is another chance for schema validation to disagree.</p>
 */
@Entity
@Table(name = "llm_model_groups")
public class LlmModelGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} means the group is global rather than owned by one white label site. */
    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "group_key", nullable = false, length = 128)
    private String groupKey;

    @Column(name = "label", nullable = false, length = 128)
    private String label;

    @Column(name = "company", length = 128)
    private String company;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmModelGroupEntity() {
    }

    public LlmModelGroupEntity(Long siteId, String groupKey, String label, String company) {
        this.siteId = siteId;
        this.groupKey = groupKey;
        this.label = label;
        this.company = company;
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

    public String getLabel() {
        return label;
    }

    public String getCompany() {
        return company;
    }

    public String getStatus() {
        return status;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}

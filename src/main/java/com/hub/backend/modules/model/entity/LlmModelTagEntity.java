package com.hub.backend.modules.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A display tag of a catalogue model.
 *
 * <p>Kept as its own table instead of the V1 {@code llm_models.tags_json} column so the mapping
 * stays portable across MySQL and H2, and so the catalogue can filter or group by tag.</p>
 */
@Entity
@Table(name = "llm_model_tags")
public class LlmModelTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "tag", nullable = false, length = 64)
    private String tag;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected LlmModelTagEntity() {
    }

    public LlmModelTagEntity(Long modelId, String tag, int sortOrder) {
        this.modelId = modelId;
        this.tag = tag;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getModelId() {
        return modelId;
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

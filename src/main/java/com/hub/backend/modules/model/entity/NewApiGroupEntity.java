package com.hub.backend.modules.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One New API relay group as published by the model plaza.
 *
 * <p>{@code group_key} is the value a relay token's {@code group} field has to carry; {@code label}
 * is the plaza's human description and {@code ratio} the group multiplier applied to prices.
 * {@code usable} mirrors whether the latest snapshot still lists it among the user-selectable
 * groups, so a removed group can be hidden without deleting the row a token may still reference.</p>
 */
@Entity
@Table(name = "newapi_groups")
public class NewApiGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_key", nullable = false, length = 64, unique = true)
    private String groupKey;

    @Column(name = "label", length = 128)
    private String label;

    @Column(name = "ratio", precision = 18, scale = 6)
    private BigDecimal ratio;

    @Column(name = "usable", nullable = false)
    private boolean usable = true;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NewApiGroupEntity() {
    }

    public NewApiGroupEntity(String groupKey, String label, BigDecimal ratio, LocalDateTime syncedAt) {
        this.groupKey = groupKey;
        this.label = label;
        this.ratio = ratio;
        this.syncedAt = syncedAt;
    }

    /** Applies a fresh snapshot row without touching the identity. */
    public void refresh(String label, BigDecimal ratio, boolean usable, LocalDateTime syncedAt) {
        this.label = label;
        this.ratio = ratio;
        this.usable = usable;
        this.syncedAt = syncedAt;
    }

    public Long getId() {
        return id;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public String getLabel() {
        return label;
    }

    public BigDecimal getRatio() {
        return ratio;
    }

    public boolean isUsable() {
        return usable;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

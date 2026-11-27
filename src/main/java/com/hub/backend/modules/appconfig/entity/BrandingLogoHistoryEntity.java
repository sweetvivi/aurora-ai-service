package com.hub.backend.modules.appconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One logo replacement event for a site chrome slot.
 *
 * <p>Admin console uses a shared API key, so there is no reliable per-operator identity —
 * we keep time + old/new URL so ops can still see what changed and when.</p>
 */
@Entity
@Table(name = "branding_logo_history")
public class BrandingLogoHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    /** API slot name: {@code default} / {@code auth} / {@code sidenav} / … */
    @Column(name = "slot_key", nullable = false, length = 32)
    private String slotKey;

    @Column(name = "old_url", length = 2048)
    private String oldUrl;

    @Column(name = "new_url", length = 2048)
    private String newUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected BrandingLogoHistoryEntity() {
    }

    public BrandingLogoHistoryEntity(Long siteId, String slotKey, String oldUrl, String newUrl) {
        this.siteId = siteId;
        this.slotKey = slotKey;
        this.oldUrl = oldUrl;
        this.newUrl = newUrl;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getSlotKey() {
        return slotKey;
    }

    public String getOldUrl() {
        return oldUrl;
    }

    public String getNewUrl() {
        return newUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

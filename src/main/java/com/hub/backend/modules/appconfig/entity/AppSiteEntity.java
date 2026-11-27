package com.hub.backend.modules.appconfig.entity;

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
 * A white label site. Every tenant scoped row in the platform points back to one of these.
 */
@Entity
@Table(name = "app_sites")
public class AppSiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_key", nullable = false, length = 64)
    private String siteKey;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "domain", length = 255)
    private String domain;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppSiteEntity() {
    }

    public AppSiteEntity(String siteKey, String displayName) {
        this.siteKey = siteKey;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDomain() {
        return domain;
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

    /** Binds the primary domain used for host based site resolution. */
    public void setDomain(String domain) {
        this.domain = domain;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}

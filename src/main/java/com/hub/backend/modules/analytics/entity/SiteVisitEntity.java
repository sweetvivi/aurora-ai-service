package com.hub.backend.modules.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "site_visits")
public class SiteVisitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    @CreationTimestamp
    @Column(name = "visited_at", nullable = false)
    private LocalDateTime visitedAt;

    protected SiteVisitEntity() {
    }

    public SiteVisitEntity(Long siteId, String path) {
        this.siteId = siteId;
        this.path = path;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getPath() {
        return path;
    }

    public LocalDateTime getVisitedAt() {
        return visitedAt;
    }
}

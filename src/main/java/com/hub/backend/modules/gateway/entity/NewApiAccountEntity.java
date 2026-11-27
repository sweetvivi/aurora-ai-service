package com.hub.backend.modules.gateway.entity;

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
 * The New API identity bound to a platform account. Password material is never persisted here.
 */
@Entity
@Table(name = "newapi_accounts")
public class NewApiAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "newapi_user_id", unique = true)
    private Long newapiUserId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /**
     * Whether this binding was created by a flow that proved both sides belong to one person.
     *
     * <p>False means "same name, no proof": the local account predates the binding, and the New API
     * user may well belong to someone else. Sign in must not hand out an existing account on that
     * basis — a name can be claimed by anyone who can register it upstream.</p>
     */
    @Column(name = "trusted", nullable = false)
    private boolean trusted;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NewApiAccountEntity() {
    }

    public NewApiAccountEntity(Long siteId, Long userId, String username) {
        this.siteId = siteId;
        this.userId = userId;
        this.username = username;
    }

    public void bindNewApiUser(Long newapiUserId) {
        if (newapiUserId != null) {
            this.newapiUserId = newapiUserId;
        }
    }

    /** Records that both sides are now known to belong to the same person. */
    public void markTrusted() {
        this.trusted = true;
    }

    /**
     * Drops the remote user id after the New API account is gone.
     *
     * <p>The row itself stays: it carries the {@code user_id} uniqueness that keeps one local
     * account mapped to one upstream identity. Keeping a stale id instead would make a later
     * registration under the same name fail the "already bound to another account" check, and the
     * binding would point at a soft-deleted New API row.</p>
     */
    public void unbindNewApiUser() {
        this.newapiUserId = null;
        this.trusted = false;
    }

    public boolean isTrusted() {
        return trusted;
    }

    public void syncFailed(String error) {
        this.lastError = error;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getNewapiUserId() {
        return newapiUserId;
    }

    public String getUsername() {
        return username;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

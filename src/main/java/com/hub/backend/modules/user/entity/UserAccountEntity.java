package com.hub.backend.modules.user.entity;

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
 * An account of a white label site.
 *
 * <p>{@code siteId} is kept as a plain column instead of an association so that the chat path can
 * resolve an owner without loading the whole site graph.</p>
 */
@Entity
@Table(name = "user_accounts")
public class UserAccountEntity {

    /** Status of an account that is allowed to use the platform. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Status of an account that is retained for audit but may no longer sign in. */
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "email", length = 255)
    private String email;

    /**
     * Whether the address above was proved to belong to this account.
     *
     * <p>This site never sends its own codes, so the only proof it can accept is New API's: a
     * registration that had to pass the upstream email verification code. An address typed into the
     * profile form is therefore stored but stays unverified.</p>
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "nickname", length = 128)
    private String nickname;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "vip_expires_at")
    private LocalDateTime vipExpiresAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "remark", length = 1024)
    private String remark;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserAccountEntity() {
    }

    public UserAccountEntity(Long siteId, String username, String nickname) {
        this.siteId = siteId;
        this.username = username;
        this.nickname = nickname;
        this.status = STATUS_ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getPhone() {
        return phone;
    }

    public String getNickname() {
        return nickname;
    }

    /** @return BCrypt hash, never the password itself */
    public String getPasswordHash() {
        return passwordHash;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public LocalDateTime getVipExpiresAt() {
        return vipExpiresAt;
    }

    public String getStatus() {
        return status;
    }

    public String getRemark() {
        return remark;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /** Records that the upstream email verification code confirmed this address. */
    public void markEmailVerified() {
        this.emailVerified = true;
    }

    /**
     * Drops the proof when the address changes.
     *
     * <p>A profile edit is a self-declared address, so whatever was proved before no longer applies
     * to the new one. Callers must not leave the old flag set.</p>
     */
    public void clearEmailVerified() {
        this.emailVerified = false;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** @return whether the account may sign in and use the platform */
    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public void markLoggedIn(LocalDateTime at) {
        this.lastLoginAt = at == null ? LocalDateTime.now() : at;
    }
}

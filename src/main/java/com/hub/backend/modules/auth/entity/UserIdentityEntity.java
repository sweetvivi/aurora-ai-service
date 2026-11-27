package com.hub.backend.modules.auth.entity;

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
 * One way of proving an account's identity.
 *
 * <p>{@code provider} names the mechanism ({@code password} today, {@code wechat} / {@code email}
 * later); {@code provider_subject} is the identifier inside it.</p>
 *
 * <p>The schema makes {@code (provider, provider_subject)} unique <em>globally</em>, not per site,
 * so the password subject is namespaced with the site key. That keeps the constraint meaningful and
 * stops the same username on two white label sites from colliding in one row.</p>
 *
 * <p>{@code provider_profile_json} is intentionally not mapped: a JSON column cannot be validated
 * reliably by Hibernate across MySQL and H2, and nothing reads it yet.</p>
 */
@Entity
@Table(name = "user_identities")
public class UserIdentityEntity {

    /** Username and password. */
    public static final String PROVIDER_PASSWORD = "password";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserIdentityEntity() {
    }

    public UserIdentityEntity(Long userId, String provider, String providerSubject) {
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = providerSubject;
    }

    /**
     * @param siteKey  white label site the account belongs to
     * @param username login name inside that site
     */
    public static String passwordSubject(String siteKey, String username) {
        return siteKey + ":" + username;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

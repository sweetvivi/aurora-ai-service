package com.hub.backend.modules.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A login session, identified by the SHA-256 of its refresh token.
 *
 * <p>The refresh secret itself is never stored, so a database leak cannot be replayed against the
 * API. Rotating a session revokes this row and issues a new one; presenting a revoked row's token
 * again is treated as replay and revokes every active session of that account.</p>
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Explicit column definition: the schema stores this as CHAR(64), while a plain String mapping
    // produces VARCHAR and would fail schema validation.
    @Column(name = "token_hash", nullable = false, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "client_type", length = 32)
    private String clientType;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuthSessionEntity() {
    }

    public AuthSessionEntity(Long userId, String tokenHash, String clientType,
                             String userAgent, String ipAddress, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.clientType = clientType;
        this.userAgent = truncate(userAgent, 512);
        this.ipAddress = truncate(ipAddress, 45);
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isActive(LocalDateTime now) {
        return !isRevoked() && !isExpired(now);
    }

    public void revoke(LocalDateTime at) {
        if (revokedAt == null) {
            this.revokedAt = at == null ? LocalDateTime.now() : at;
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getClientType() {
        return clientType;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

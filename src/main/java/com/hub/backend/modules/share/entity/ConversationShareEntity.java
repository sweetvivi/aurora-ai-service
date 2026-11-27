package com.hub.backend.modules.share.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A stable read-only view of a conversation at the moment it was shared.
 *
 * <p>{@code tokenHash} is the SHA-256 of the link token handed to the client. The database cannot
 * know the token itself, so a leaked row cannot mint a working link.</p>
 */
@Entity
@Table(name = "conversation_shares")
public class ConversationShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "share_token", nullable = false, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "snapshot_json", columnDefinition = "LONGTEXT")
    private String snapshotJson;

    public ConversationShareEntity(Long conversationId, Long userId, String tokenHash, String title,
                                   LocalDateTime expiresAt, String snapshotJson) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.title = title;
        this.expiresAt = expiresAt;
        this.snapshotJson = snapshotJson;
        this.revokedAt = null;
    }

    protected ConversationShareEntity() {
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getTitle() {
        return title;
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

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void revoke(LocalDateTime at) {
        this.revokedAt = at == null ? LocalDateTime.now() : at;
    }
}

package com.hub.backend.modules.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 一个用户在一个 New API 分组上的 Relay Token。
 *
 * <p>New API 的分组是 Token 上的单值字段，而同一个模型可能只在别的分组里有渠道。用户一旦请求
 * 那种模型，就必须换一把该分组的 Token —— 复用同一把会让上游报
 * {@code No available channel for model ... under group default}。</p>
 *
 * <p>这里只存「这把钥匙是什么」，不存余额：扣费与配额仍然只由 New API 决定，
 * 与 {@link UserGatewayTokenEntity} 的边界一致。</p>
 */
@Entity
@Table(name = "newapi_group_tokens")
public class NewApiGroupTokenEntity {

    public static final String STATUS_PROVISIONING = "PROVISIONING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    /** New API 的分组名，原样保留（可能是中文），不能改写大小写或做 slug。 */
    @Column(name = "group_key", nullable = false, length = 64)
    private String groupKey;

    @Column(name = "token_name", nullable = false, unique = true, length = 50)
    private String tokenName;

    @Column(name = "newapi_token_id", unique = true)
    private Long newapiTokenId;

    @Lob
    @Column(name = "key_ciphertext")
    private byte[] keyCiphertext;

    @Column(name = "key_nonce", length = 12)
    private byte[] keyNonce;

    @Column(name = "key_tag", length = 16)
    private byte[] keyTag;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_PROVISIONING;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NewApiGroupTokenEntity() {
    }

    public NewApiGroupTokenEntity(Long userId, Long siteId, String groupKey, String tokenName) {
        this.userId = userId;
        this.siteId = siteId;
        this.groupKey = groupKey;
        this.tokenName = tokenName;
    }

    public boolean isReady() {
        return STATUS_ACTIVE.equals(status) && hasEncryptedKey();
    }

    public boolean hasEncryptedKey() {
        return keyCiphertext != null && keyNonce != null && keyTag != null;
    }

    public void claim(String owner, LocalDateTime leaseUntil) {
        this.status = STATUS_PROVISIONING;
        this.leaseOwner = owner;
        this.leaseUntil = leaseUntil;
    }

    public void complete(Long newapiTokenId,
                         byte[] ciphertext,
                         byte[] nonce,
                         byte[] tag) {
        this.newapiTokenId = newapiTokenId;
        this.keyCiphertext = ciphertext;
        this.keyNonce = nonce;
        this.keyTag = tag;
        this.status = STATUS_ACTIVE;
        this.lastError = null;
        this.attempt = 0;
        this.nextRetryAt = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public void failed(String error, LocalDateTime retryAt) {
        this.status = STATUS_ERROR;
        this.lastError = error;
        this.attempt += 1;
        this.nextRetryAt = retryAt;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    /** New API 那边这把钥匙已经没了：清掉本地密文，下次用到会重新建。 */
    public void invalidate() {
        this.newapiTokenId = null;
        this.keyCiphertext = null;
        this.keyNonce = null;
        this.keyTag = null;
        this.status = STATUS_PROVISIONING;
        this.nextRetryAt = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public String getTokenName() {
        return tokenName;
    }

    public Long getNewapiTokenId() {
        return newapiTokenId;
    }

    public byte[] getKeyCiphertext() {
        return keyCiphertext;
    }

    public byte[] getKeyNonce() {
        return keyNonce;
    }

    public byte[] getKeyTag() {
        return keyTag;
    }

    public String getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public int getAttempt() {
        return attempt;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }
}

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

@Entity
@Table(name = "user_gateway_tokens")
public class UserGatewayTokenEntity {

    public static final String STATUS_PROVISIONING = "PROVISIONING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "newapi_user_id", unique = true)
    private Long newapiUserId;

    @Column(name = "newapi_token_id", unique = true)
    private Long newapiTokenId;

    @Column(name = "newapi_pat_id", unique = true)
    private Long newapiPatId;

    @Lob
    @Column(name = "pat_ciphertext")
    private byte[] patCiphertext;

    @Column(name = "pat_nonce", length = 12)
    private byte[] patNonce;

    @Column(name = "pat_tag", length = 16)
    private byte[] patTag;

    @Column(name = "pat_scope_version", nullable = false)
    private int patScopeVersion;

    @Column(name = "token_name", nullable = false, unique = true, length = 50)
    private String tokenName;

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

    @Column(name = "last_usage_error", length = 512)
    private String lastUsageError;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "total_granted", nullable = false)
    private long totalGranted;

    @Column(name = "total_used", nullable = false)
    private long totalUsed;

    @Column(name = "total_available", nullable = false)
    private long totalAvailable;

    @Column(name = "unlimited_quota", nullable = false)
    private boolean unlimitedQuota;

    @Lob
    @Column(name = "model_limits")
    private String modelLimits;

    /**
     * New API 的 {@code model_limits_enabled} 开关，与用量快照一起缓存。
     *
     * <p>用量响应里除了配额三列还有这个布尔值。缓存命中时不查上游，只能从库里还原 ——
     * 缺这一列就只好硬编码默认值，界面上的「模型限制」开关会与实际不符。</p>
     */
    @Column(name = "model_limits_enabled", nullable = false)
    private boolean modelLimitsEnabled;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserGatewayTokenEntity() {
    }

    public UserGatewayTokenEntity(Long userId, Long siteId, String tokenName) {
        this.userId = userId;
        this.siteId = siteId;
        this.tokenName = tokenName;
        this.status = STATUS_PROVISIONING;
    }

    public void claim(String siteIdTokenName, Long siteId, String owner, LocalDateTime until) {
        this.siteId = siteId;
        this.status = STATUS_PROVISIONING;
        this.tokenName = siteIdTokenName;
        this.attempt++;
        this.leaseOwner = owner;
        this.leaseUntil = until;
        this.nextRetryAt = null;
    }

    public void bindNewApiUser(Long userId) {
        this.newapiUserId = userId;
    }

    public void bindPat(Long patId, byte[] ciphertext, byte[] nonce, byte[] tag,
                        int scopeVersion) {
        this.newapiPatId = patId;
        this.patCiphertext = ciphertext;
        this.patNonce = nonce;
        this.patTag = tag;
        this.patScopeVersion = scopeVersion;
    }

    public void beginPatScopeUpgrade(String owner, LocalDateTime until) {
        this.leaseOwner = owner;
        this.leaseUntil = until;
    }

    public void completePatScopeUpgrade(int scopeVersion) {
        this.patScopeVersion = scopeVersion;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public void complete(Long tokenId, String name, byte[] ciphertext, byte[] nonce, byte[] tag,
                         LocalDateTime now) {
        this.newapiTokenId = tokenId;
        this.tokenName = name;
        this.keyCiphertext = ciphertext;
        this.keyNonce = nonce;
        this.keyTag = tag;
        this.status = STATUS_ACTIVE;
        this.lastError = null;
        this.lastUsageError = null;
        this.nextRetryAt = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.lastSyncAt = now;
    }

    public void updateUsage(long granted, long used, long available, boolean unlimited,
                            String limitsJson, boolean limitsEnabled,
                            LocalDateTime expiresAt, LocalDateTime syncedAt) {
        this.totalGranted = granted;
        this.totalUsed = used;
        this.totalAvailable = available;
        this.unlimitedQuota = unlimited;
        this.modelLimits = limitsJson;
        this.modelLimitsEnabled = limitsEnabled;
        this.expiresAt = expiresAt;
        this.lastSyncAt = syncedAt;
        this.lastError = null;
        this.lastUsageError = null;
        this.nextRetryAt = null;
    }

    public void provisioningFailed(String error, LocalDateTime retryAt) {
        this.status = STATUS_ERROR;
        this.lastError = error;
        this.nextRetryAt = retryAt;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    /**
     * Drops the backoff a failed provisioning attempt left behind.
     *
     * <p>Called once the account's New API identity has just been (re)established — a password
     * mirrored on sign in, for example — because whatever the last attempt tripped over is gone.
     * Without this the wallet keeps answering "still provisioning" for the rest of the retry delay
     * immediately after the user did the one thing that fixes it, which reads as "signing in again
     * did not help". The status stays {@code ERROR} on purpose: the row is only ready once a later
     * attempt actually finishes, and {@link #claim} treats a cleared backoff as claimable.</p>
     */
    public void clearRetryBackoff() {
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void usageFailed(String error) {
        this.lastUsageError = error;
    }

    public void disableAndClearCredentials() {
        this.status = STATUS_DISABLED;
        this.patCiphertext = null;
        this.patNonce = null;
        this.patTag = null;
        this.keyCiphertext = null;
        this.keyNonce = null;
        this.keyTag = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.nextRetryAt = null;
    }

    public boolean hasEncryptedPat() {
        return patCiphertext != null && patCiphertext.length > 0
                && patNonce != null && patTag != null;
    }

    public boolean hasEncryptedKey() {
        return keyCiphertext != null && keyCiphertext.length > 0
                && keyNonce != null && keyTag != null;
    }

    public boolean isReady() {
        return STATUS_ACTIVE.equals(status)
                && newapiUserId != null
                && newapiTokenId != null
                && newapiPatId != null
                && hasEncryptedPat()
                && hasEncryptedKey();
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

    public Long getNewapiUserId() {
        return newapiUserId;
    }

    public Long getNewapiTokenId() {
        return newapiTokenId;
    }

    public Long getNewapiPatId() {
        return newapiPatId;
    }

    public byte[] getPatCiphertext() {
        return patCiphertext;
    }

    public byte[] getPatNonce() {
        return patNonce;
    }

    public byte[] getPatTag() {
        return patTag;
    }

    public int getPatScopeVersion() {
        return patScopeVersion;
    }

    public String getTokenName() {
        return tokenName;
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

    public String getLastUsageError() {
        return lastUsageError;
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

    public long getTotalGranted() {
        return totalGranted;
    }

    public long getTotalUsed() {
        return totalUsed;
    }

    public long getTotalAvailable() {
        return totalAvailable;
    }

    public boolean isUnlimitedQuota() {
        return unlimitedQuota;
    }

    public String getModelLimits() {
        return modelLimits;
    }

    public boolean isModelLimitsEnabled() {
        return modelLimitsEnabled;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

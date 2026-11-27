package com.hub.backend.modules.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.hub.backend.modules.gateway.entity.UserGatewayTokenEntity;
import com.hub.backend.modules.gateway.repository.UserGatewayTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserGatewayTokenStateStore {

    private final UserGatewayTokenRepository tokens;

    public UserGatewayTokenStateStore(UserGatewayTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public Claim claim(Long userId, Long siteId, String tokenName, String owner,
                       LocalDateTime now, LocalDateTime leaseUntil) {
        UserGatewayTokenEntity entity = tokens.findByUserIdForUpdate(userId)
                .orElseGet(() -> tokens.saveAndFlush(
                        new UserGatewayTokenEntity(userId, siteId, tokenName)));

        if (entity.isReady()) {
            return new Claim(ClaimStatus.READY, Snapshot.of(entity));
        }
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now)) {
            return new Claim(ClaimStatus.BUSY, Snapshot.of(entity));
        }
        if (entity.getNextRetryAt() != null && entity.getNextRetryAt().isAfter(now)) {
            return new Claim(ClaimStatus.BUSY, Snapshot.of(entity));
        }

        entity.claim(tokenName, siteId, owner, leaseUntil);
        tokens.saveAndFlush(entity);
        return new Claim(ClaimStatus.ACQUIRED, Snapshot.of(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot bindNewApiUser(Long userId, String owner, Long newapiUserId) {
        UserGatewayTokenEntity entity = requireOwnedLease(userId, owner);
        entity.bindNewApiUser(newapiUserId);
        return Snapshot.of(tokens.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot bindPat(Long userId, String owner, Long patId,
                            byte[] ciphertext, byte[] nonce, byte[] tag,
                            int scopeVersion) {
        UserGatewayTokenEntity entity = requireOwnedLease(userId, owner);
        entity.bindPat(patId, ciphertext, nonce, tag, scopeVersion);
        return Snapshot.of(tokens.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public PatScopeUpgradeClaim beginPatScopeUpgrade(Long userId,
                                                     String owner,
                                                     int requiredVersion,
                                                     LocalDateTime now,
                                                     LocalDateTime leaseUntil) {
        UserGatewayTokenEntity entity = tokens.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("User gateway token disappeared"));
        Snapshot snapshot = Snapshot.of(entity);
        if (entity.getPatScopeVersion() >= requiredVersion) {
            return new PatScopeUpgradeClaim(PatScopeUpgradeStatus.COMPLETE, snapshot);
        }
        if (entity.getNewapiPatId() == null || !entity.hasEncryptedPat()) {
            return new PatScopeUpgradeClaim(PatScopeUpgradeStatus.NOT_READY, snapshot);
        }
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now)) {
            return new PatScopeUpgradeClaim(PatScopeUpgradeStatus.BUSY, snapshot);
        }
        entity.beginPatScopeUpgrade(owner, leaseUntil);
        tokens.saveAndFlush(entity);
        return new PatScopeUpgradeClaim(PatScopeUpgradeStatus.ACQUIRED, Snapshot.of(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot completePatScopeUpgrade(Long userId, String owner, int scopeVersion) {
        UserGatewayTokenEntity entity = requireOwnedLease(userId, owner);
        entity.completePatScopeUpgrade(scopeVersion);
        return Snapshot.of(tokens.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandonPatScopeUpgrade(Long userId, String owner) {
        UserGatewayTokenEntity entity = tokens.findByUserIdForUpdate(userId).orElse(null);
        if (entity != null && owner != null && owner.equals(entity.getLeaseOwner())) {
            entity.completePatScopeUpgrade(entity.getPatScopeVersion());
            tokens.saveAndFlush(entity);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot complete(Long userId, String owner, Long tokenId, String tokenName,
                             byte[] ciphertext, byte[] nonce, byte[] tag, LocalDateTime now) {
        UserGatewayTokenEntity entity = requireOwnedLease(userId, owner);
        entity.complete(tokenId, tokenName, ciphertext, nonce, tag, now);
        return Snapshot.of(tokens.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisioningFailed(Long userId, String owner, String error, LocalDateTime retryAt) {
        UserGatewayTokenEntity entity = requireOwnedLease(userId, owner);
        entity.provisioningFailed(error, retryAt);
        tokens.saveAndFlush(entity);
    }

    /**
     * Lets the next request retry provisioning immediately, instead of waiting out the backoff of a
     * failed attempt.
     *
     * <p>Used when the account's New API identity was just synchronised, which removes the reason
     * the previous attempt failed. A missing row simply means nothing was ever attempted.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearProvisioningBackoff(Long userId) {
        tokens.findByUserIdForUpdate(userId).ifPresent(entity -> {
            entity.clearRetryBackoff();
            tokens.saveAndFlush(entity);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot updateUsage(Long userId, JsonNode limits, long granted, long used,
                                long available, boolean unlimited, boolean limitsEnabled,
                                long expiresAt, LocalDateTime now) {
        UserGatewayTokenEntity entity = tokens.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("User gateway token disappeared"));
        LocalDateTime expiresAtTime = expiresAt <= 0
                ? null
                : LocalDateTime.ofEpochSecond(expiresAt, 0, java.time.ZoneOffset.UTC);
        entity.updateUsage(granted, used, available, unlimited, limits.toString(), limitsEnabled,
                expiresAtTime, now);
        return Snapshot.of(tokens.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void usageFailed(Long userId, String error, LocalDateTime now) {
        UserGatewayTokenEntity entity = tokens.findByUserIdForUpdate(userId).orElse(null);
        if (entity != null) {
            entity.usageFailed(error);
            tokens.saveAndFlush(entity);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disableAndClearCredentials(Long userId) {
        tokens.findByUserIdForUpdate(userId).ifPresent(entity -> {
            entity.disableAndClearCredentials();
            tokens.saveAndFlush(entity);
        });
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> find(Long userId) {
        return tokens.findByUserId(userId).map(Snapshot::of);
    }

    private UserGatewayTokenEntity requireOwnedLease(Long userId, String owner) {
        UserGatewayTokenEntity entity = tokens.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("User gateway token disappeared"));
        if (owner == null || !owner.equals(entity.getLeaseOwner())) {
            throw new IllegalStateException("User gateway token lease was lost");
        }
        return entity;
    }

    public enum ClaimStatus {
        READY,
        ACQUIRED,
        BUSY
    }

    public record Claim(ClaimStatus status, Snapshot token) {
    }

    public enum PatScopeUpgradeStatus {
        COMPLETE,
        ACQUIRED,
        BUSY,
        NOT_READY
    }

    public record PatScopeUpgradeClaim(
            PatScopeUpgradeStatus status,
            Snapshot token
    ) {
    }

    public record Snapshot(
            Long id,
            Long userId,
            Long siteId,
            Long newapiUserId,
            Long newapiTokenId,
            Long newapiPatId,
            byte[] patCiphertext,
            byte[] patNonce,
            byte[] patTag,
            int patScopeVersion,
            String tokenName,
            byte[] keyCiphertext,
            byte[] keyNonce,
            byte[] keyTag,
            String status,
            String lastError,
            String lastUsageError,
            int attempt,
            long totalGranted,
            long totalUsed,
            long totalAvailable,
            boolean unlimitedQuota,
            String modelLimits,
            boolean modelLimitsEnabled,
            LocalDateTime expiresAt,
            LocalDateTime lastSyncAt
    ) {
        static Snapshot of(UserGatewayTokenEntity entity) {
            return new Snapshot(
                    entity.getId(),
                    entity.getUserId(),
                    entity.getSiteId(),
                    entity.getNewapiUserId(),
                    entity.getNewapiTokenId(),
                    entity.getNewapiPatId(),
                    entity.getPatCiphertext(),
                    entity.getPatNonce(),
                    entity.getPatTag(),
                    entity.getPatScopeVersion(),
                    entity.getTokenName(),
                    entity.getKeyCiphertext(),
                    entity.getKeyNonce(),
                    entity.getKeyTag(),
                    entity.getStatus(),
                    entity.getLastError(),
                    entity.getLastUsageError(),
                    entity.getAttempt(),
                    entity.getTotalGranted(),
                    entity.getTotalUsed(),
                    entity.getTotalAvailable(),
                    entity.isUnlimitedQuota(),
                    entity.getModelLimits(),
                    entity.isModelLimitsEnabled(),
                    entity.getExpiresAt(),
                    entity.getLastSyncAt());
        }
    }
}

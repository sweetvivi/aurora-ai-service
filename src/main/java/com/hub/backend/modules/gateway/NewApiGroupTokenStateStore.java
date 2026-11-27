package com.hub.backend.modules.gateway;

import com.hub.backend.modules.gateway.entity.NewApiGroupTokenEntity;
import com.hub.backend.modules.gateway.repository.NewApiGroupTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 分组令牌的租约与落库边界。
 *
 * <p>与 {@link UserGatewayTokenStateStore} 同一套规矩：抢占、完成、失败各在自己的短事务里，
 * 且都带行锁或串行化隔离。上游 {@code /api/token/} 的调用必须发生在事务之外 —— 一次
 * HTTP 往返占着行锁会把并发的同组请求全部堵在这里。</p>
 */
@Service
public class NewApiGroupTokenStateStore {

    private final NewApiGroupTokenRepository tokens;

    public NewApiGroupTokenStateStore(NewApiGroupTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public Claim claim(Long userId, Long siteId, String groupKey, String tokenName,
                       String owner, LocalDateTime now, LocalDateTime leaseUntil) {
        NewApiGroupTokenEntity entity = tokens.findByUserIdAndGroupKeyForUpdate(userId, groupKey)
                .orElseGet(() -> tokens.saveAndFlush(
                        new NewApiGroupTokenEntity(userId, siteId, groupKey, tokenName)));

        if (entity.isReady()) {
            return new Claim(ClaimStatus.READY, Snapshot.of(entity));
        }
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().isAfter(now)) {
            return new Claim(ClaimStatus.BUSY, Snapshot.of(entity));
        }
        if (entity.getNextRetryAt() != null && entity.getNextRetryAt().isAfter(now)) {
            // 和 BUSY 分开：这是「上一次开通失败后的退避」，等下去也没用，得把上次的错误说出来。
            return new Claim(ClaimStatus.BACKOFF, Snapshot.of(entity));
        }

        entity.claim(owner, leaseUntil);
        tokens.saveAndFlush(entity);
        return new Claim(ClaimStatus.ACQUIRED, Snapshot.of(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Snapshot complete(Long userId, String groupKey, String owner, Long tokenId,
                             byte[] ciphertext, byte[] nonce, byte[] tag) {
        NewApiGroupTokenEntity entity = requireOwnedLease(userId, groupKey, owner);
        entity.complete(tokenId, ciphertext, nonce, tag);
        return Snapshot.of(tokens.saveAndFlush(entity));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long userId, String groupKey, String owner, String error,
                       LocalDateTime retryAt) {
        NewApiGroupTokenEntity entity = requireOwnedLease(userId, groupKey, owner);
        entity.failed(error, retryAt);
        tokens.saveAndFlush(entity);
    }

    /**
     * 上游已经不认这把钥匙（或被换掉了）：清掉密文，让下一次用到时重建。
     *
     * <p>只是本地状态，所以不要求租约 —— 拿到的可能是一把已经被别人重建过的钥匙，
     * 那种情况下清空同样是对的。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(Long userId, String groupKey) {
        tokens.findByUserIdAndGroupKeyForUpdate(userId, groupKey)
                .ifPresent(entity -> {
                    entity.invalidate();
                    tokens.saveAndFlush(entity);
                });
    }

    @Transactional(readOnly = true)
    public java.util.List<Snapshot> findAll(Long userId) {
        return tokens.findByUserId(userId).stream().map(Snapshot::of).toList();
    }

    private NewApiGroupTokenEntity requireOwnedLease(Long userId, String groupKey, String owner) {
        NewApiGroupTokenEntity entity = tokens
                .findByUserIdAndGroupKeyForUpdate(userId, groupKey)
                .orElseThrow(() -> new IllegalStateException("Group token row disappeared"));
        if (owner == null || !owner.equals(entity.getLeaseOwner())) {
            throw new IllegalStateException("Group token lease was lost");
        }
        return entity;
    }

    public enum ClaimStatus {
        /** 密文已在本地，直接用。 */
        READY,
        /** 本次抢到了租约，调用方负责去上游建。 */
        ACQUIRED,
        /** 另一个请求正持有租约在开通；等一会儿通常就能复用它的结果。 */
        BUSY,
        /** 上一次开通失败，还在退避窗口内；此时重试必然再失败，应把上次的错误说出来。 */
        BACKOFF
    }

    public record Claim(ClaimStatus status, Snapshot token) {
    }

    public record Snapshot(
            Long id,
            Long userId,
            Long siteId,
            String groupKey,
            String tokenName,
            Long newapiTokenId,
            byte[] keyCiphertext,
            byte[] keyNonce,
            byte[] keyTag,
            String status,
            String lastError
    ) {
        static Snapshot of(NewApiGroupTokenEntity entity) {
            return new Snapshot(
                    entity.getId(),
                    entity.getUserId(),
                    entity.getSiteId(),
                    entity.getGroupKey(),
                    entity.getTokenName(),
                    entity.getNewapiTokenId(),
                    entity.getKeyCiphertext(),
                    entity.getKeyNonce(),
                    entity.getKeyTag(),
                    entity.getStatus(),
                    entity.getLastError());
        }

        boolean hasKey() {
            return keyCiphertext != null && keyCiphertext.length > 0
                    && keyNonce != null && keyTag != null;
        }
    }
}

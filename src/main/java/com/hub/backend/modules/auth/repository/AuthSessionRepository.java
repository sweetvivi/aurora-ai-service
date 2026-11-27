package com.hub.backend.modules.auth.repository;

import com.hub.backend.modules.auth.entity.AuthSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, Long> {

    /**
     * Finds a session by the hash of its refresh token, including revoked and expired rows: reuse
     * of a rotated token has to be recognised, which requires seeing the revoked row.
     */
    Optional<AuthSessionEntity> findByTokenHash(String tokenHash);

    /**
     * @return how many sessions were revoked
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSessionEntity s set s.revokedAt = :at "
            + "where s.userId = :userId and s.revokedAt is null and s.expiresAt > :at")
    int revokeActiveSessions(@Param("userId") Long userId, @Param("at") LocalDateTime at);

    /**
     * Revokes every other active session after a credential change.
     *
     * @return how many sessions were revoked
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSessionEntity s set s.revokedAt = :at "
            + "where s.userId = :userId and s.id <> :currentSessionId "
            + "and s.revokedAt is null and s.expiresAt > :at")
    int revokeOtherActiveSessions(@Param("userId") Long userId,
                                  @Param("currentSessionId") Long currentSessionId,
                                  @Param("at") LocalDateTime at);
}

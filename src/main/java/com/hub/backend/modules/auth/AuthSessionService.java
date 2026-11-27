package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.modules.auth.entity.AuthSessionEntity;
import com.hub.backend.modules.auth.repository.AuthSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Lifecycle of refresh sessions.
 *
 * <p>Rotation revokes the presented session and issues a new one. Presenting an already rotated
 * token is treated as theft: a leaked refresh token that has been replayed means the attacker and
 * the real client both hold it, so every active session of the account is dropped and the user has
 * to sign in again.</p>
 */
@Service
public class AuthSessionService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final AuthSessionRepository repository;
    private final TokenService tokenService;
    private final AuthProperties authProperties;
    private final TransactionTemplate revokeInOwnTransaction;

    public AuthSessionService(AuthSessionRepository repository,
                              TokenService tokenService,
                              AuthProperties authProperties,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.authProperties = authProperties;
        // The replay reaction has to survive the exception that rejects the request, so it is
        // committed in its own transaction.
        this.revokeInOwnTransaction = new TransactionTemplate(transactionManager);
        this.revokeInOwnTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public IssuedSession startSession(Long userId, String clientType, String userAgent, String ipAddress) {
        String refreshToken = tokenService.newRefreshToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(authProperties.getRefreshTokenTtl());

        AuthSessionEntity session = repository.save(new AuthSessionEntity(
                userId, tokenService.hashToken(refreshToken), clientType, userAgent, ipAddress, expiresAt));

        return new IssuedSession(session, refreshToken);
    }

    /**
     * @throws UnauthorizedException when the token is unknown, expired, or has already been rotated
     */
    @Transactional
    public IssuedSession rotate(String refreshToken, String userAgent, String ipAddress) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Refresh token is missing");
        }

        AuthSessionEntity session = repository.findByTokenHash(tokenService.hashToken(refreshToken))
                .orElseThrow(() -> new UnauthorizedException("Refresh token is not recognised"));

        LocalDateTime now = LocalDateTime.now();
        if (session.isRevoked()) {
            int revoked = revokeInOwnTransaction.execute(status ->
                    repository.revokeActiveSessions(session.getUserId(), now));
            log.warn("Refresh token replay detected for user {}: revoked {} other active session(s)",
                    session.getUserId(), revoked);
            throw new UnauthorizedException("Refresh token has already been used");
        }
        if (session.isExpired(now)) {
            throw new UnauthorizedException("Refresh token has expired");
        }

        session.revoke(now);
        return startSession(session.getUserId(), session.getClientType(), userAgent, ipAddress);
    }

    /**
     * @throws UnauthorizedException when the session was revoked or has expired
     */
    @Transactional(readOnly = true)
    public AuthSessionEntity requireActiveSession(Long sessionId) {
        AuthSessionEntity session = repository.findById(sessionId)
                .orElseThrow(() -> new UnauthorizedException("Session is no longer valid"));

        if (!session.isActive(LocalDateTime.now())) {
            throw new UnauthorizedException("Session is no longer valid");
        }
        return session;
    }

    @Transactional
    public void revokeSession(Long sessionId) {
        repository.findById(sessionId).ifPresent(session -> session.revoke(LocalDateTime.now()));
    }

    @Transactional
    public int revokeOtherSessions(Long userId, Long currentSessionId) {
        return repository.revokeOtherActiveSessions(
                userId, currentSessionId, LocalDateTime.now());
    }

    @Transactional
    public int revokeAllSessions(Long userId) {
        return repository.revokeActiveSessions(userId, LocalDateTime.now());
    }

    /**
     * @param session      persisted session, its id becomes the {@code sid} claim
     * @param refreshToken secret handed to the client; never persisted in clear text
     */
    public record IssuedSession(AuthSessionEntity session, String refreshToken) {
    }
}

package com.hub.backend.testsupport;

import com.hub.backend.modules.auth.AuthSessionService;
import com.hub.backend.modules.auth.TokenService;
import com.hub.backend.modules.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issues real access tokens for accounts seeded directly by HTTP controller tests.
 */
public abstract class AuthenticatedControllerTestSupport {

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @BeforeEach
    void clearAuthenticationSessions() {
        authSessionRepository.deleteAll();
    }

    protected String accessToken(Long userId) {
        AuthSessionService.IssuedSession session = authSessionService.startSession(
                userId, "test", "controller-test", "127.0.0.1");
        return tokenService.issueAccessToken(userId, session.session().getId()).token();
    }
}

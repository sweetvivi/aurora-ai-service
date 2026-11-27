package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.modules.auth.dto.AuthTokenResponse;
import com.hub.backend.modules.auth.dto.LoginRequest;
import com.hub.backend.modules.auth.entity.AuthSessionEntity;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.user.AccountDataPurger;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An account that only exists on New API may sign in here, which is the whole point of making the
 * username the shared identity. The three outcomes are tested separately because treating them
 * alike is how a login endpoint turns into a username oracle.
 */
class NewApiAccountLoginTest {

    private static final long SITE_ID = 1L;
    private static final String SITE_KEY = "site-a";
    private static final String USERNAME = "bob";
    private static final String PASSWORD = "correct-horse-battery";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserAccountRepository accountRepository;
    private UserIdentityRepository identityRepository;
    private AuthSessionService sessionService;
    private TokenService tokenService;
    private AuthProperties authProperties;
    private NewApiAccountIdentityService accountIdentity;
    private SessionPasswordVault passwordVault;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(UserAccountRepository.class);
        identityRepository = mock(UserIdentityRepository.class);
        sessionService = mock(AuthSessionService.class);
        tokenService = mock(TokenService.class);
        authProperties = new AuthProperties();
        accountIdentity = mock(NewApiAccountIdentityService.class);
        passwordVault = mock(SessionPasswordVault.class);

        when(accountRepository.findFirstBySiteIdAndUsername(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(accountRepository.save(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity account = invocation.getArgument(0);
            if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", 42L);
            }
            return account;
        });
        when(tokenService.issueAccessToken(any(), any()))
                .thenReturn(new TokenService.IssuedToken("access-token", Instant.parse("2026-10-30T01:00:00Z")));

        authService = new AuthService(
                accountRepository,
                identityRepository,
                sessionService,
                tokenService,
                passwordEncoder,
                authProperties,
                mock(UserService.class),
                mock(RegistrationRateLimiter.class),
                mock(CaptchaService.class),
                mock(UserGatewayTokenService.class),
                accountIdentity,
                passwordVault,
                mock(FileService.class), mock(AccountDataPurger.class));
    }

    @Test
    void signsInANameThatOnlyExistsOnNewApi() {
        stubSession();
        when(accountIdentity.loginWithCandidate(USERNAME, PASSWORD))
                .thenReturn(new NewApiLoginSession(77L, USERNAME, "login-token"));

        AuthTokenResponse response = authService.login(
                SITE_ID, SITE_KEY, new LoginRequest(USERNAME, PASSWORD, "web"), context());

        assertEquals("access-token", response.accessToken());
        assertEquals(USERNAME, response.user().username());
        verify(accountIdentity).bindIdentity(
                42L, SITE_ID, USERNAME, 77L, true);
        verify(passwordVault).remember(42L, PASSWORD);
    }

    @Test
    void refusesAPasswordNewApiRejectsWithoutCreatingAnything() {
        when(accountIdentity.loginWithCandidate(USERNAME, PASSWORD))
                .thenThrow(new RuntimeException("rejected"));

        assertThrows(UnauthorizedException.class, () -> authService.login(
                SITE_ID, SITE_KEY, new LoginRequest(USERNAME, PASSWORD, "web"), context()));

        verify(accountIdentity).loginWithCandidate(USERNAME, PASSWORD);
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void refusesWithoutAskingNewApiWhenProvisioningIsDisabled() {
        authProperties.setNewApiAccountProvisioningEnabled(false);

        assertThrows(UnauthorizedException.class, () -> authService.login(
                SITE_ID, SITE_KEY, new LoginRequest(USERNAME, PASSWORD, "web"), context()));

        verify(accountIdentity, never()).loginWithCandidate(anyString(), anyString());
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    /**
     * The reported bug: registering {@code demo} on New API and signing in here used to hand over
     * the existing local {@code demo} — its history, files and balance — because a shared name was
     * treated as proof of ownership.
     */
    @Test
    void doesNotHandOverAnExistingAccountJustBecauseTheNameMatchesUpstream() {
        UserAccountEntity existing = new UserAccountEntity(SITE_ID, USERNAME, USERNAME);
        existing.setPasswordHash(passwordEncoder.encode("the-old-local-password"));
        when(accountRepository.findFirstBySiteIdAndUsername(SITE_ID, USERNAME))
                .thenReturn(Optional.of(existing));

        assertThrows(UnauthorizedException.class, () -> authService.login(
                SITE_ID, SITE_KEY,
                new LoginRequest(USERNAME, "password-i-registered-upstream", "web"), context()));

        // The local hash must survive untouched, or the account would be taken over silently.
        assertTrue(passwordEncoder.matches(
                "the-old-local-password", existing.getPasswordHash()));
        verify(accountIdentity, never()).loginWithCandidate(anyString(), anyString());
    }

    @Test
    void acceptsAnExistingAccountUsingOnlyItsLocalPassword() {
        stubSession();
        UserAccountEntity existing = new UserAccountEntity(SITE_ID, USERNAME, USERNAME);
        existing.setPasswordHash(passwordEncoder.encode(PASSWORD));
        ReflectionTestUtils.setField(existing, "id", 42L);
        when(accountRepository.findFirstBySiteIdAndUsername(SITE_ID, USERNAME))
                .thenReturn(Optional.of(existing));

        AuthTokenResponse response = authService.login(
                SITE_ID, SITE_KEY,
                new LoginRequest(USERNAME, PASSWORD, "web"), context());

        assertEquals("access-token", response.accessToken());
        verify(accountIdentity, never()).loginWithCandidate(anyString(), anyString());
        verify(passwordVault).remember(42L, PASSWORD);
    }

    private void stubSession() {
        AuthSessionEntity row = mock(AuthSessionEntity.class);
        when(row.getId()).thenReturn(5L);
        when(sessionService.startSession(any(), any(), any(), any()))
                .thenReturn(new AuthSessionService.IssuedSession(row, "refresh-token"));
    }

    private static ClientContext context() {
        return new ClientContext("web", "JUnit", "127.0.0.1");
    }
}

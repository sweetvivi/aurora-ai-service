package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiRegistrationException;
import com.hub.backend.modules.auth.dto.AuthTokenResponse;
import com.hub.backend.modules.auth.dto.RegisterRequest;
import com.hub.backend.modules.auth.entity.AuthSessionEntity;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.user.AccountDataPurger;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Registration has to leave the same account on both sides.
 *
 * <p>The local row alone is not enough: every AI call signs in to New API as the user, so a
 * registration that only exists here looks successful and fails at the first generation. These
 * tests pin the ordering (remote first, local second) and the rule that a refusal upstream never
 * turns into a local account.</p>
 */
class NewApiRegistrationTest {

    private static final long SITE_ID = 1L;
    private static final String SITE_KEY = "site-a";
    private static final String USERNAME = "bob";
    private static final String PASSWORD = "correct-horse-battery";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserAccountRepository accountRepository;
    private NewApiAccountIdentityService accountIdentity;
    private SessionPasswordVault passwordVault;
    private AuthProperties authProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(UserAccountRepository.class);
        accountIdentity = mock(NewApiAccountIdentityService.class);
        passwordVault = mock(SessionPasswordVault.class);
        authProperties = new AuthProperties();

        when(accountRepository.existsBySiteIdAndUsername(anyLong(), anyString())).thenReturn(false);
        when(accountRepository.save(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity account = invocation.getArgument(0);
            if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", 42L);
            }
            return account;
        });

        AuthSessionService sessionService = mock(AuthSessionService.class);
        AuthSessionEntity row = mock(AuthSessionEntity.class);
        when(row.getId()).thenReturn(5L);
        when(sessionService.startSession(any(), any(), any(), any()))
                .thenReturn(new AuthSessionService.IssuedSession(row, "refresh-token"));

        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueAccessToken(any(), any()))
                .thenReturn(new TokenService.IssuedToken(
                        "access-token", Instant.parse("2026-10-30T01:00:00Z")));

        authService = new AuthService(
                accountRepository,
                mock(com.hub.backend.modules.auth.repository.UserIdentityRepository.class),
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
    void createsTheNewApiAccountFirstAndBindsItAsTrusted() {
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, null, null))
                .thenReturn(new NewApiLoginSession(77L, USERNAME, "login-token"));

        AuthTokenResponse response = authService.register(
                SITE_ID, SITE_KEY, registration(), context());

        assertEquals("access-token", response.accessToken());
        verify(accountIdentity).registerAccount(USERNAME, PASSWORD, null, null);
        verify(accountIdentity).bindIdentity(42L, SITE_ID, USERNAME, 77L, true);
        verify(passwordVault).remember(42L, PASSWORD);
    }

    @Test
    void failsInsteadOfCreatingALocalAccountWhenRegistrationIsClosedUpstream() {
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, null, null))
                .thenThrow(new NewApiRegistrationException(
                        NewApiRegistrationException.Kind.DISABLED, "registration disabled"));

        assertThrows(ServiceUnavailableException.class,
                () -> authService.register(SITE_ID, SITE_KEY, registration(), context()));

        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void explainsAnEmailVerificationDemandInsteadOfSilentlySkippingIt() {
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, null, null))
                .thenThrow(new NewApiRegistrationException(
                        NewApiRegistrationException.Kind.EMAIL_VERIFICATION_REQUIRED,
                        "Email verification is enabled"));

        ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                () -> authService.register(SITE_ID, SITE_KEY, registration(), context()));

        assertTrue(failure.getMessage().contains("邮箱验证"));
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void tellsTheUserWhyRegistrationIsUnavailableInsteadOfReusingTheGatewayWording() {
        // 网关那句 "New API integration is disabled" 会一路显示到注册页：用户看不懂，
        // 也不知道该找谁。注册这一层要换成一句短话，并且说明找谁。
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, null, null))
                .thenThrow(new ServiceUnavailableException("New API integration is disabled"));

        ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                () -> authService.register(SITE_ID, SITE_KEY, registration(), context()));

        assertTrue(failure.getMessage().contains("请联系管理员"));
        assertFalse(failure.getMessage().contains("New API"));
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void claimsANameAnEarlierAttemptLeftBehindOnNewApiOnly() {
        // The remote account exists and the same password opens it: this is an interrupted earlier
        // attempt, not somebody else's account.
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, null, null))
                .thenThrow(new NewApiRegistrationException(
                        NewApiRegistrationException.Kind.ALREADY_EXISTS, "Username already exists"));
        when(accountIdentity.loginWithCandidate(USERNAME, PASSWORD))
                .thenReturn(new NewApiLoginSession(77L, USERNAME, "login-token"));

        authService.register(SITE_ID, SITE_KEY, registration(), context());

        verify(accountIdentity).bindIdentity(42L, SITE_ID, USERNAME, 77L, true);
        verify(passwordVault).remember(42L, PASSWORD);
    }

    @Test
    void refusesANameOwnedBySomebodyElseOnNewApi() {
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, null, null))
                .thenThrow(new NewApiRegistrationException(
                        NewApiRegistrationException.Kind.ALREADY_EXISTS, "Username already exists"));
        when(accountIdentity.loginWithCandidate(USERNAME, PASSWORD))
                .thenThrow(new NewApiHttpException(401, "rejected"));

        assertThrows(ConflictException.class,
                () -> authService.register(SITE_ID, SITE_KEY, registration(), context()));

        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void staysLocalWhenTheRemoteRegistrationIsTurnedOff() {
        authProperties.setNewApiRegistrationEnabled(false);

        authService.register(SITE_ID, SITE_KEY, registration(), context());

        verifyNoInteractions(accountIdentity);
        verify(passwordVault).remember(42L, PASSWORD);
    }

    private static RegisterRequest registration() {
        return new RegisterRequest(
                USERNAME, PASSWORD, null, null, null, "web", "captcha-id", "captcha-code");
    }

    private static ClientContext context() {
        return new ClientContext("web", "JUnit", "127.0.0.1");
    }
}

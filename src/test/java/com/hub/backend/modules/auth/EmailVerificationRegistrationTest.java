package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.ForbiddenException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiRegistrationException;
import com.hub.backend.modules.auth.dto.AuthTokenResponse;
import com.hub.backend.modules.auth.dto.LoginRequest;
import com.hub.backend.modules.auth.dto.RegisterRequest;
import com.hub.backend.modules.auth.dto.RegistrationOptionsResponse;
import com.hub.backend.modules.auth.entity.AuthSessionEntity;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.user.AccountDataPurger;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

/**
 * Email verification belongs to New API, so what this site can be held to is narrow: it must carry
 * the address and the code through untouched, must not mint a code of its own, and must not mark an
 * address as proved unless the upstream code was actually accepted.
 */
class EmailVerificationRegistrationTest {

    private static final long SITE_ID = 1L;
    private static final String SITE_KEY = "site-a";
    private static final String USERNAME = "bob";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String EMAIL = "bob@example.com";
    private static final String CODE = "483920";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserAccountRepository accountRepository;
    private UserIdentityRepository identityRepository;
    private NewApiAccountIdentityService accountIdentity;
    private AuthProperties authProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(UserAccountRepository.class);
        identityRepository = mock(UserIdentityRepository.class);
        accountIdentity = mock(NewApiAccountIdentityService.class);
        authProperties = new AuthProperties();

        when(accountRepository.existsBySiteIdAndUsername(anyLong(), anyString())).thenReturn(false);
        when(accountRepository.existsBySiteIdAndEmail(anyLong(), anyString())).thenReturn(false);
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
                mock(SessionPasswordVault.class),
                mock(FileService.class), mock(AccountDataPurger.class));
    }

    @Test
    void asksForAnAddressAndCodeOnceTheUpstreamRequiresThem() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);

        InvalidRequestException missingAddress = assertThrows(InvalidRequestException.class,
                () -> authService.register(SITE_ID, SITE_KEY,
                        registration(null, null), context()));
        assertTrue(missingAddress.getMessage().contains("邮箱地址"));

        InvalidRequestException missingCode = assertThrows(InvalidRequestException.class,
                () -> authService.register(SITE_ID, SITE_KEY,
                        registration(EMAIL, null), context()));
        assertTrue(missingCode.getMessage().contains("验证码"));

        verify(accountIdentity, never()).registerAccount(anyString(), anyString(), any(), any());
    }

    @Test
    void forwardsTheAddressAndCodeAndMarksTheAccountVerified() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, EMAIL, CODE))
                .thenReturn(new NewApiLoginSession(77L, USERNAME, "login-token"));

        authService.register(SITE_ID, SITE_KEY, registration(EMAIL, CODE), context());

        ArgumentCaptor<UserAccountEntity> saved = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(accountRepository).save(saved.capture());
        assertEquals(EMAIL, saved.getValue().getEmail());
        assertTrue(saved.getValue().isEmailVerified());
    }

    @Test
    void leavesTheAddressUnverifiedWhenNoCodeWasProved() {
        // Upstream verification is off: an address typed into the form is a claim, not a proof.
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(false);
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, EMAIL, null))
                .thenReturn(new NewApiLoginSession(77L, USERNAME, "login-token"));

        authService.register(SITE_ID, SITE_KEY, registration(EMAIL, null), context());

        ArgumentCaptor<UserAccountEntity> saved = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(accountRepository).save(saved.capture());
        assertEquals(EMAIL, saved.getValue().getEmail());
        assertFalse(saved.getValue().isEmailVerified());
    }

    @Test
    void reportsATakenAddressAsAConflictRatherThanClaimingTheAccount() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, EMAIL, CODE))
                .thenThrow(new NewApiRegistrationException(
                        NewApiRegistrationException.Kind.EMAIL_TAKEN,
                        "Email address is already in use"));

        assertThrows(ConflictException.class,
                () -> authService.register(SITE_ID, SITE_KEY, registration(EMAIL, CODE), context()));

        verify(accountIdentity, never()).loginWithCandidate(anyString(), anyString());
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void reportsAMistypedCodeAsABadRequest() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);
        when(accountIdentity.registerAccount(USERNAME, PASSWORD, EMAIL, CODE))
                .thenThrow(new NewApiRegistrationException(
                        NewApiRegistrationException.Kind.VERIFICATION_CODE_INVALID,
                        "Verification code is incorrect or has expired"));

        InvalidRequestException failure = assertThrows(InvalidRequestException.class,
                () -> authService.register(SITE_ID, SITE_KEY, registration(EMAIL, CODE), context()));

        assertTrue(failure.getMessage().contains("验证码"));
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    @Test
    void refusesSignInWhenTheAddressIsNotVerified() {
        authProperties.getEmailVerification().setLoginRequiresVerifiedEmail(true);

        UserAccountEntity account = activeAccount();
        when(accountRepository.findFirstBySiteIdAndUsername(SITE_ID, USERNAME))
                .thenReturn(Optional.of(account));

        assertThrows(ForbiddenException.class, () -> authService.login(
                SITE_ID, SITE_KEY, new LoginRequest(USERNAME, PASSWORD, "web"), context()));
    }

    @Test
    void signsInOnceTheAddressIsVerified() {
        authProperties.getEmailVerification().setLoginRequiresVerifiedEmail(true);

        UserAccountEntity account = activeAccount();
        account.markEmailVerified();
        when(accountRepository.findFirstBySiteIdAndUsername(SITE_ID, USERNAME))
                .thenReturn(Optional.of(account));

        AuthTokenResponse response = authService.login(
                SITE_ID, SITE_KEY, new LoginRequest(USERNAME, PASSWORD, "web"), context());

        assertEquals("access-token", response.accessToken());
    }

    @Test
    void hidesTheCodeEndpointWhenTheUpstreamDoesNotVerifyEmail() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(false);

        assertThrows(NotFoundException.class,
                () -> authService.sendRegistrationEmailCode(SITE_ID, EMAIL));
    }

    @Test
    void reportsARequiredAddressEvenWhenNoCodeCanBeSent() {
        /*
         * register-mode=ON is an instruction, not a fact about the upstream: it keeps asking for an
         * address even when the deployment cannot mail a code. Reporting both flags as one value
         * would either drop the requirement or promise a code that never arrives, so the two must
         * disagree here.
         */
        authProperties.getEmailVerification().setRegisterMode(
                AuthProperties.EmailVerification.RegisterMode.ON);
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(false);

        RegistrationOptionsResponse options = authService.registrationOptions();

        assertTrue(options.emailVerificationRequired());
        assertFalse(options.emailCodeAvailable());
    }

    @Test
    void reportsACodeAsAvailableWhenTheUpstreamVerifiesAddresses() {
        // AUTO follows the upstream, so both answers come from the same fact and agree.
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);

        RegistrationOptionsResponse options = authService.registrationOptions();

        assertTrue(options.emailVerificationRequired());
        assertTrue(options.emailCodeAvailable());
    }

    @Test
    void answersLocallyWhenRegistrationIsLocalOnly() {
        // AUTH_NEW_API_REGISTRATION_ENABLED=false must stay an offline switch: no upstream call,
        // and no email field even if the deployment verifies addresses.
        authProperties.setNewApiRegistrationEnabled(false);
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);

        RegistrationOptionsResponse options = authService.registrationOptions();

        assertFalse(options.emailVerificationRequired());
        assertFalse(options.emailCodeAvailable());
        verify(accountIdentity, never()).isEmailVerificationEnabled();
    }

    @Test
    void hidesTheCodeEndpointWhenRegistrationIsLocalOnly() {
        // No registration can consume a code here, so forwarding the request would only mail one.
        authProperties.setNewApiRegistrationEnabled(false);
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);

        assertThrows(NotFoundException.class,
                () -> authService.sendRegistrationEmailCode(SITE_ID, EMAIL));

        verify(accountIdentity, never()).sendVerificationCode(anyString());
    }

    @Test
    void refusesToMailACodeForAnAddressThisSiteAlreadyUses() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);
        when(accountRepository.existsBySiteIdAndEmail(SITE_ID, EMAIL)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> authService.sendRegistrationEmailCode(SITE_ID, EMAIL));

        verify(accountIdentity, never()).sendVerificationCode(anyString());
    }

    @Test
    void forwardsTheCodeRequestWithoutStoringAnything() {
        when(accountIdentity.isEmailVerificationEnabled()).thenReturn(true);

        authService.sendRegistrationEmailCode(SITE_ID, EMAIL);

        verify(accountIdentity).sendVerificationCode(EMAIL);
        verify(accountRepository, never()).save(any(UserAccountEntity.class));
    }

    private UserAccountEntity activeAccount() {
        UserAccountEntity account = new UserAccountEntity(SITE_ID, USERNAME, USERNAME);
        ReflectionTestUtils.setField(account, "id", 42L);
        account.setPasswordHash(passwordEncoder.encode(PASSWORD));
        account.setEmail(EMAIL);
        return account;
    }

    private static RegisterRequest registration(String email, String emailCode) {
        return new RegisterRequest(USERNAME, PASSWORD, null, email, emailCode,
                "web", "captcha-id", "captcha-code");
    }

    private static ClientContext context() {
        return new ClientContext("web", "JUnit", "127.0.0.1");
    }
}

package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.auth.dto.DeleteAccountRequest;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting a local account has to reach New API with a password this server can still read.
 *
 * <p>The gateway call needs the plaintext password twice (to sign in, and to satisfy New API's
 * step-up proof), and the vault holding it is emptied by restarts and expiry. The deletion form is
 * the one place a fresh copy is available, so it must be written to the vault <em>before</em> the
 * cleanup runs — otherwise the remote account silently survives, which is what these tests pin
 * down.</p>
 */
class NewApiAccountDeletionTest {

    private static final long USER_ID = 42L;
    private static final long SITE_ID = 1L;
    private static final String USERNAME = "bob";
    private static final String PASSWORD = "correct-horse-battery";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserAccountRepository accountRepository;
    private UserIdentityRepository identityRepository;
    private AuthSessionService sessionService;
    private UserGatewayTokenService gatewayTokenService;
    private SessionPasswordVault passwordVault;
    private AccountDataPurger accountDataPurger;
    private AuthService authService;
    private UserAccountEntity account;

    @BeforeEach
    void setUp() {
        accountRepository = mock(UserAccountRepository.class);
        identityRepository = mock(UserIdentityRepository.class);
        sessionService = mock(AuthSessionService.class);
        gatewayTokenService = mock(UserGatewayTokenService.class);
        passwordVault = new SessionPasswordVault(java.time.Clock.systemUTC(), new AuthProperties());
        accountDataPurger = mock(AccountDataPurger.class);

        account = new UserAccountEntity(SITE_ID, USERNAME, "Bob");
        ReflectionTestUtils.setField(account, "id", USER_ID);
        account.setPasswordHash(passwordEncoder.encode(PASSWORD));

        UserService userService = mock(UserService.class);
        when(userService.requireById(USER_ID)).thenReturn(account);

        authService = new AuthService(
                accountRepository,
                identityRepository,
                sessionService,
                mock(TokenService.class),
                passwordEncoder,
                new AuthProperties(),
                userService,
                mock(RegistrationRateLimiter.class),
                mock(CaptchaService.class),
                gatewayTokenService,
                mock(NewApiAccountIdentityService.class),
                passwordVault,
                mock(FileService.class), accountDataPurger);
    }

    @Test
    void handsTheTypedPasswordToTheGatewaySoTheRemoteAccountCanBeDeleted() {
        // The session predates a restart: nothing is held for this user yet.
        assertTrue(passwordVault.password(USER_ID).isEmpty());

        authService.deleteAccount(USER_ID, 5L,
                new DeleteAccountRequest(PASSWORD, "注销账号"));

        // Cleanup must observe the password; a call ordering bug is invisible to a plain verify().
        InOrder order = inOrder(gatewayTokenService, sessionService);
        order.verify(gatewayTokenService).cleanupAccountAccess(any());
        order.verify(sessionService).revokeAllSessions(USER_ID);
    }

    @Test
    void deletesTheRemoteAccountWithThePasswordFromTheDeletionForm() {
        SessionPasswordVault vault =
                new SessionPasswordVault(java.time.Clock.systemUTC(), new AuthProperties());
        UserGatewayTokenService realGateway = mock(UserGatewayTokenService.class);

        // Stands in for the real cleanup, which reads the vault at the moment it is called.
        String[] seenPassword = new String[1];
        String[] sawUsername = new String[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            seenPassword[0] = vault.password(USER_ID).orElse(null);
            sawUsername[0] = invocation.getArgument(0, CurrentUser.class).username();
            return null;
        }).when(realGateway).cleanupAccountAccess(any());

        AuthService service = new AuthService(
                accountRepository,
                identityRepository,
                sessionService,
                mock(TokenService.class),
                passwordEncoder,
                new AuthProperties(),
                userServiceReturning(account),
                mock(RegistrationRateLimiter.class),
                mock(CaptchaService.class),
                realGateway,
                mock(NewApiAccountIdentityService.class),
                vault,
                mock(FileService.class), mock(AccountDataPurger.class));

        service.deleteAccount(USER_ID, 5L, new DeleteAccountRequest(PASSWORD, "注销账号"));

        // Without this the gateway cannot sign in to New API, and the remote account survives.
        assertEquals(PASSWORD, seenPassword[0]);
        assertEquals(USERNAME, sawUsername[0]);
    }

    @Test
    void forgetsThePasswordOnceTheAccountIsGone() {
        authService.deleteAccount(USER_ID, 5L, new DeleteAccountRequest(PASSWORD, "注销账号"));

        assertTrue(passwordVault.password(USER_ID).isEmpty());
    }

    @Test
    void refusesBeforeTouchingNewApiWhenThePasswordIsWrong() {
        assertThrows(RuntimeException.class,
                () -> authService.deleteAccount(USER_ID, 5L,
                        new DeleteAccountRequest("wrong-password", "注销账号")));

        verify(gatewayTokenService, never()).cleanupAccountAccess(any());
    }

    @Test
    void refusesBeforeTouchingNewApiWhenTheConfirmationTextIsWrong() {
        assertThrows(RuntimeException.class,
                () -> authService.deleteAccount(USER_ID, 5L,
                        new DeleteAccountRequest(PASSWORD, "注销")));

        verify(gatewayTokenService, never()).cleanupAccountAccess(any());
        assertTrue(passwordVault.password(USER_ID).isEmpty());
    }

    @Test
    void clearsTheNewApiBindingAndTheLocalIdentity() {
        authService.deleteAccount(USER_ID, 5L, new DeleteAccountRequest(PASSWORD, "注销账号"));

        verify(identityRepository).deleteByUserId(USER_ID);
        // The row is removed, not disabled: a DISABLED placeholder would keep the username
        // reserved and the owner could never register that name again.
        verify(accountDataPurger).purge(USER_ID);
        verify(accountRepository).delete(account);
        verify(accountRepository, never()).save(account);
    }

    private UserService userServiceReturning(UserAccountEntity row) {
        UserService userService = mock(UserService.class);
        when(userService.requireById(USER_ID)).thenReturn(row);
        return userService;
    }
}

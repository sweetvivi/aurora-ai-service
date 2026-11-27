package com.hub.backend.modules.redemption;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.modules.auth.SessionPasswordConfirmationService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.redemption.dto.UserRedemptionResponse;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedemptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");
    private static final String PASSWORD = "correct-horse-battery";

    private NewApiManagementClient newApi;
    private NewApiAccountIdentityService accountIdentity;
    private UserGatewayTokenService gatewayTokens;
    private SessionPasswordVault passwords;
    private PasswordEncoder passwordEncoder;
    private RedemptionService service;

    @BeforeEach
    void setUp() {
        newApi = mock(NewApiManagementClient.class);
        accountIdentity = mock(NewApiAccountIdentityService.class);
        gatewayTokens = mock(UserGatewayTokenService.class);
        passwords = mock(SessionPasswordVault.class);
        passwordEncoder = mock(PasswordEncoder.class);

        UserService users = mock(UserService.class);
        UserAccountEntity account = new UserAccountEntity(1L, "demo", "demo");
        account.setPasswordHash("stored-hash");
        when(users.requireById(USER.userId())).thenReturn(account);
        when(passwordEncoder.matches(PASSWORD, "stored-hash")).thenReturn(true);

        SessionPasswordConfirmationService confirmation = new SessionPasswordConfirmationService(
                users, passwordEncoder, passwords);

        NewApiProperties properties = new NewApiProperties();
        properties.setQuotaPerUnit(new BigDecimal("500000"));
        properties.setUsdExchangeRate(new BigDecimal("7.3"));
        service = new RedemptionService(
                newApi,
                accountIdentity,
                gatewayTokens,
                passwords,
                confirmation,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsThePublicCommonRedemptionAmounts() {
        assertEquals(
                List.of(new BigDecimal("10"), new BigDecimal("30"),
                        new BigDecimal("50"), new BigDecimal("100"), new BigDecimal("300")),
                service.userOptions().commonAmounts());
    }

    @Test
    void redeemsThroughTheCurrentUsersLoginAndRefreshesTheirUsage() {
        when(passwords.password(USER.userId())).thenReturn(Optional.of(PASSWORD));
        NewApiLoginSession login = new NewApiLoginSession(77L, "demo", "login-token");
        when(accountIdentity.ensureLogin(USER)).thenReturn(login);
        when(newApi.redeemRedemption("login-token", "ABCD1234EFGH5678"))
                .thenReturn(1_369_863L);

        UserRedemptionResponse response = service.redeem(USER, "  ABCD1234EFGH5678  ", null);

        assertEquals(1_369_863L, response.quota());
        assertEquals(new BigDecimal("20.00"), response.amountCny());
        assertEquals("ABCD****5678", response.maskedCode());
        assertEquals(NOW, response.redeemedAt());
        verify(accountIdentity).ensureLogin(USER);
        verify(newApi).redeemRedemption("login-token", "ABCD1234EFGH5678");
        verify(gatewayTokens).refreshUsageSnapshot(USER);
        // The vault still had the password, so no hash comparison is spent on this request.
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void asksForThePasswordInsteadOfSigningTheUserOutWhenTheVaultLostIt() {
        when(passwords.password(USER.userId())).thenReturn(Optional.empty());

        assertThrows(
                PasswordConfirmationRequiredException.class,
                () -> service.redeem(USER, "ABCD1234EFGH5678", null));

        verify(accountIdentity, never()).ensureLogin(any());
        verify(newApi, never()).redeemRedemption(any(), any());
    }

    @Test
    void redeemsWithTheConfirmedPasswordWhenTheVaultLostIt() {
        when(passwords.password(USER.userId())).thenReturn(Optional.empty());
        when(accountIdentity.ensureLogin(USER)).thenReturn(
                new NewApiLoginSession(77L, "demo", "login-token"));
        when(newApi.redeemRedemption("login-token", "ABCD1234EFGH5678")).thenReturn(500_000L);

        service.redeem(USER, "ABCD1234EFGH5678", PASSWORD);

        // The verified password goes back into the vault for the rest of the session.
        verify(passwords).remember(USER.userId(), PASSWORD);
        verify(accountIdentity).ensureLogin(USER);
    }

    @Test
    void refusesAWrongPasswordWithoutTouchingNewApi() {
        when(passwords.password(USER.userId())).thenReturn(Optional.empty());
        when(passwordEncoder.matches("wrong-password", "stored-hash")).thenReturn(false);

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> service.redeem(USER, "ABCD1234EFGH5678", "wrong-password"));

        assertEquals("当前密码不正确", exception.getMessage());
        verify(passwords, never()).remember(any(), any());
        verify(accountIdentity, never()).ensureLogin(any());
    }

    @Test
    void mapsARedemptionHttpRejectionToAnInvalidCodeMessage() {
        when(passwords.password(USER.userId())).thenReturn(Optional.of(PASSWORD));
        when(accountIdentity.ensureLogin(USER)).thenReturn(
                new NewApiLoginSession(77L, "demo", "login-token"));
        when(newApi.redeemRedemption("login-token", "ABCD1234EFGH5678"))
                .thenThrow(new NewApiHttpException(400, "invalid code"));

        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> service.redeem(USER, "ABCD1234EFGH5678", null));

        assertEquals("兑换码无效、已使用或已过期", exception.getMessage());
        verify(gatewayTokens, never()).refreshUsageSnapshot(any());
    }

    @Test
    void mapsABusinessRejectionToAnInvalidCodeMessage() {
        when(passwords.password(USER.userId())).thenReturn(Optional.of(PASSWORD));
        when(accountIdentity.ensureLogin(USER)).thenReturn(
                new NewApiLoginSession(77L, "demo", "login-token"));
        when(newApi.redeemRedemption("login-token", "ABCD1234EFGH5678"))
                .thenThrow(new LlmInvocationException(
                        "New API rejected the request at /api/user/topup"));

        assertThrows(
                InvalidRequestException.class,
                () -> service.redeem(USER, "ABCD1234EFGH5678", null));

        verify(gatewayTokens, never()).refreshUsageSnapshot(any());
    }

    @Test
    void rejectsAnInvalidCodeBeforeAskingForAPasswordOrSigningIn() {
        assertThrows(
                InvalidRequestException.class,
                () -> service.redeem(USER, "short", null));

        verify(passwordEncoder, never()).matches(any(), any());
        verify(accountIdentity, never()).ensureLogin(any());
        verify(newApi, never()).redeemRedemption(any(), any());
    }
}

package com.hub.backend.modules.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiAccessToken;
import com.hub.backend.infra.newapi.NewApiAccessTokenCredential;
import com.hub.backend.infra.newapi.NewApiConsumptionLog;
import com.hub.backend.infra.newapi.NewApiConsumptionLogPage;
import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiToken;
import com.hub.backend.infra.newapi.NewApiTokenUsage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.crypto.AesGcmTokenCipher;
import com.hub.backend.modules.gateway.deletion.NewApiAccountDeletionQueue;
import com.hub.backend.modules.gateway.entity.NewApiAccountEntity;
import com.hub.backend.modules.gateway.entity.NewApiDeletionTaskEntity;
import com.hub.backend.modules.gateway.repository.NewApiAccountRepository;
import com.hub.backend.modules.gateway.dto.TokenConsumptionLogResponse;
import com.hub.backend.modules.gateway.dto.TokenUsageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserGatewayTokenServiceTest {

    private static final long USER_ID = 7L;
    private static final long SITE_ID = 1L;
    private static final long NEW_API_USER_ID = 77L;
    private static final long PAT_ID = 88L;
    private static final long RELAY_TOKEN_ID = 99L;
    private static final String TOKEN_NAME = "hub-1-u7";
    private static final String PAT_NAME = "hub-pat-1-u7";
    private static final String LOGIN_TOKEN = "login-token";
    private static final String OLD_PAT = "old-user-pat";
    private static final String NEW_PAT = "new-user-pat";
    private static final String RELAY_KEY = "sk-user-relay-key";
    private static final String USERNAME = "demo";
    private static final String MIRRORED_PASSWORD = "mirrored-password";
    private static final CurrentUser CURRENT_USER = new CurrentUser(USER_ID, SITE_ID, USERNAME);

    private UserGatewayTokenStateStore stateStore;
    private NewApiManagementClient newApi;
    /** The same instance the service holds, so a test can turn the administrator on. */
    private NewApiProperties properties;
    private AesGcmTokenCipher cipher;
    private SessionPasswordVault passwords;
    private NewApiAccountDeletionQueue deletionQueue;
    private UserGatewayTokenService service;

    @BeforeEach
    void setUp() {
        stateStore = mock(UserGatewayTokenStateStore.class);
        newApi = mock(NewApiManagementClient.class);
        deletionQueue = mock(NewApiAccountDeletionQueue.class);

        NewApiProperties properties = new NewApiProperties();
        this.properties = properties;
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new AesGcmTokenCipher(properties);
        // The session holds the password: provisioning signs in to New API with it.
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
        passwords = new SessionPasswordVault(clock, new AuthProperties());
        passwords.remember(USER_ID, MIRRORED_PASSWORD);
        NewApiAccountEntity identity = new NewApiAccountEntity(SITE_ID, USER_ID, USERNAME);
        identity.bindNewApiUser(NEW_API_USER_ID);
        NewApiAccountRepository accounts = mock(NewApiAccountRepository.class);
        when(accounts.findByUserId(anyLong())).thenReturn(Optional.of(identity));
        when(accounts.save(any(NewApiAccountEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new UserGatewayTokenService(
                stateStore,
                mock(NewApiGroupTokenStateStore.class),
                newApi,
                new NewApiAccountIdentityService(newApi, properties, accounts, passwords),
                properties,
                cipher,
                passwords,
                new ObjectMapper(),
                clock,
                deletionQueue);
    }

    @Test
    void requireRelayCredentialRejectsAZeroUserBalance() {
        claimReady(RELAY_KEY);
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(0));

        PaymentRequiredException exception = assertThrows(
                PaymentRequiredException.class,
                () -> service.requireRelayCredential(CURRENT_USER));

        assertEquals(UpstreamFailureText.INSUFFICIENT_BALANCE_TEXT, exception.getMessage());
    }

    @Test
    void requireRelayKeyRetriesAfterAConcurrentClaimDeadlock() {
        UserGatewayTokenStateStore.Snapshot ready = readySnapshot(RELAY_KEY, 100);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, ready));

        assertEquals(RELAY_KEY, service.requireRelayKey(CURRENT_USER));

        verify(stateStore, times(2)).claim(
                eq(USER_ID), eq(SITE_ID), eq(TOKEN_NAME), anyString(), any(), any());
    }

    @Test
    void usageReturnsAZeroBalanceInsteadOfRejectingIt() {
        UserGatewayTokenStateStore.Snapshot initial = readySnapshot(RELAY_KEY, 0);
        UserGatewayTokenStateStore.Snapshot updated = readySnapshot(RELAY_KEY, 0);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, initial));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(initial), Optional.of(updated));
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(0));

        TokenUsageResponse response = service.usage(CURRENT_USER);

        assertEquals(0, response.totalAvailable());
        assertEquals("sk-use...-key", response.maskedKey());
        assertEquals(TOKEN_NAME, response.tokenName());
    }

    @Test
    void usagePersistsAndReturnsTheUsersQuotaRatherThanTheRelayTokensQuota() {
        UserGatewayTokenStateStore.Snapshot initial = readySnapshot(RELAY_KEY, 80);
        UserGatewayTokenStateStore.Snapshot updated = readySnapshot(RELAY_KEY, 70);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, initial));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(initial), Optional.of(updated));
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(
                usageWithTokenAndUserQuota(0, 30, 70));

        TokenUsageResponse response = service.usage(CURRENT_USER);

        verify(stateStore).updateUsage(
                eq(USER_ID), any(), eq(100L), eq(30L), eq(70L),
                eq(false), eq(false), eq(0L), any(LocalDateTime.class));
        assertEquals(100, response.totalGranted());
        assertEquals(30, response.totalUsed());
        assertEquals(70, response.totalAvailable());
    }

    @Test
    void usageReturnsTheUserBalanceInCnyUsingTheNewApiExchangeRate() {        UserGatewayTokenStateStore.Snapshot initial =
                readySnapshot(RELAY_KEY, 600000, 100000, 500000);
        UserGatewayTokenStateStore.Snapshot updated =
                readySnapshot(RELAY_KEY, 600000, 100000, 500000);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, initial));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(initial), Optional.of(updated));
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(
                usageWithTokenAndUserQuota(0, 100000, 500000));

        TokenUsageResponse response = service.usage(CURRENT_USER);

        assertEquals("CNY", response.currency());
        assertEquals(new BigDecimal("8.76"), response.totalGrantedCny());
        assertEquals(new BigDecimal("1.46"), response.totalUsedCny());
        assertEquals(new BigDecimal("7.30"), response.totalAvailableCny());
        /*
         * 美元金额由前端用这两个常量自己算（quota / quotaPerUnit），接口必须把口径一起给出去，
         * 否则前台只能硬编码 500000，后台改了额度单位就会和 New API 显示对不上。
         */
        assertEquals(new BigDecimal("500000"), response.quotaPerUnit());
        assertEquals(new BigDecimal("7.3"), response.usdExchangeRate());
    }

    // ---------------------------------------------------------------------------------------
    // 用量快照缓存：`/api/usage/token/` 与 `/api/user/login` 共用 20 次 / 20 分钟的限流，
    // 每次 AI 调用都实时查一次，几轮论文生成就把配额烧光、开始回 429。
    // ---------------------------------------------------------------------------------------

    @Test
    void usageForceRefreshQueriesNewApiEvenWhenTheSnapshotIsWithinTtl() {
        // 2 分钟前的快照仍在 3 分钟 TTL 内（缓存可用），但用户刚扣过费，
        // 显式 refresh 必须穿透缓存去对账，否则侧栏还要旧最多 3 分钟。
        UserGatewayTokenStateStore.Snapshot cached = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-19T23:58:00"));
        UserGatewayTokenStateStore.Snapshot updated = readySnapshot(RELAY_KEY, 70);
        UserGatewayTokenStateStore.Snapshot charged = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-20T00:00:00"), false, 60);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, cached));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(cached), Optional.of(charged));
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(60));

        TokenUsageResponse response = service.usage(CURRENT_USER, true);

        verify(newApi).fetchUsage(RELAY_KEY);
        assertEquals(60, response.totalAvailable());
    }

    @Test
    void usageForceRefreshKeepsTheCacheWhenTheSnapshotWasJustSynced() {
        // 刚同步过（0 秒）就走的是「页面在死循环刷新」那条路：必须被最小间隔挡住，
        // 否则 New API 的 20 次 / 20 分钟额度会被这种刷新吃光。
        UserGatewayTokenStateStore.Snapshot fresh = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-20T00:00:00"));
        stubReadySnapshot(fresh);

        TokenUsageResponse response = service.usage(CURRENT_USER, true);

        verify(newApi, never()).fetchUsage(anyString());
        assertEquals(70, response.totalAvailable());
    }

    @Test
    void usageDoesNotCallNewApiWhileTheSnapshotIsStillFresh() {
        // 快照时间与固定时钟一致 => 未过期，不该再打上游。
        UserGatewayTokenStateStore.Snapshot fresh = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-20T00:00:00"));
        stubReadySnapshot(fresh);

        TokenUsageResponse response = service.usage(CURRENT_USER);

        verify(newApi, never()).fetchUsage(anyString());
        verify(stateStore, never()).updateUsage(
                anyLong(), any(), anyLong(), anyLong(), anyLong(),
                anyBoolean(), anyBoolean(), anyLong(), any());
        assertEquals(70, response.totalAvailable());
    }

    @Test
    void requireRelayCredentialReusesAFreshSnapshotInsteadOfQueryingNewApi() {
        UserGatewayTokenStateStore.Snapshot fresh = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-20T00:00:00"));
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, fresh));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(fresh));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));

        verify(newApi, never()).fetchUsage(anyString());
    }

    @Test
    void requireRelayCredentialQueriesNewApiAgainOnceTheSnapshotAgesOut() {
        UserGatewayTokenStateStore.Snapshot stale = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-19T00:00:00"));
        UserGatewayTokenStateStore.Snapshot updated = readySnapshot(RELAY_KEY, 70);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, stale));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(stale), Optional.of(updated));
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(70));

        service.requireRelayCredential(CURRENT_USER);

        verify(newApi).fetchUsage(RELAY_KEY);
    }

    @Test
    void anUnlimitedRelayTokenIsNotTreatedAsBankruptByAZeroAvailable() {
        // 不限额令牌的 total_available 天然是 0，误判会把每一次 AI 调用都挡在 402。
        UserGatewayTokenStateStore.Snapshot unlimited = snapshotWithSyncAt(
                LocalDateTime.parse("2026-10-20T00:00:00"), true, 0);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, unlimited));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(unlimited));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));
    }

    @Test
    void consumptionLogsReturnNewApiTokensChargesAndStreamingState() {
        UserGatewayTokenStateStore.Snapshot snapshot = snapshotWithCredentials(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION);
        stubReadySnapshot(snapshot);
        when(newApi.fetchConsumptionLogs(OLD_PAT, 2, 20)).thenReturn(
                new NewApiConsumptionLogPage(
                        2,
                        20,
                        41,
                        List.of(new NewApiConsumptionLog(
                                901,
                                2,
                                1_893_456_000L,
                                "gpt-5",
                                TOKEN_NAME,
                                20_548,
                                120,
                                80,
                                3,
                                true))));

        TokenConsumptionLogResponse response =
                service.consumptionLogs(CURRENT_USER, 2, 20);

        assertEquals(2, response.page());
        assertEquals(20, response.pageSize());
        assertEquals(41, response.total());
        TokenConsumptionLogResponse.Item item = response.items().get(0);
        assertEquals(901, item.id());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), item.createdAt());
        assertEquals("gpt-5", item.modelName());
        assertEquals(120, item.promptTokens());
        assertEquals(80, item.completionTokens());
        assertEquals(200, item.totalTokens());
        assertEquals(20_548, item.quota());
        assertEquals(new BigDecimal("0.3000"), item.amountCny());
        assertEquals(3, item.useTime());
        assertTrue(item.stream());
        assertEquals("consume", item.type());
        verify(stateStore, never()).beginPatScopeUpgrade(
                anyLong(), anyString(), anyInt(), any(), any());
    }

    /**
     * 失败任务的退款行必须在同一份日志里出现。
     *
     * <p>只查 {@code type=2} 时退款行会被上游过滤掉：用户看到扣费、看不到退回，
     * 从账面上就是「钱凭空少了」。这里固定住 6 → {@code refund} 的映射。</p>
     */
    @Test
    void refundRowsAreLabelledAsRefund() {
        stubReadySnapshot(snapshotWithCredentials(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION));
        when(newApi.fetchConsumptionLogs(OLD_PAT, 1, 20)).thenReturn(
                new NewApiConsumptionLogPage(
                        1,
                        20,
                        1,
                        List.of(new NewApiConsumptionLog(
                                902,
                                6,
                                1_893_456_000L,
                                "sora-2",
                                TOKEN_NAME,
                                90_000,
                                0,
                                0,
                                0,
                                false))));

        TokenConsumptionLogResponse response =
                service.consumptionLogs(CURRENT_USER, 1, 20);

        TokenConsumptionLogResponse.Item item = response.items().get(0);
        assertEquals("refund", item.type());
        assertEquals("sora-2", item.modelName());
        assertEquals(90_000, item.quota());
        assertEquals(0, item.totalTokens());
    }

    @Test
    void upgradesAnOldPatInPlaceBeforeReadingConsumptionLogs() {
        UserGatewayTokenStateStore.Snapshot snapshot = snapshotWithCredentials(0);
        stubOldPatUpgrade(snapshot, UserGatewayTokenStateStore.PatScopeUpgradeStatus.ACQUIRED);
        when(newApi.fetchConsumptionLogs(OLD_PAT, 1, 20)).thenReturn(emptyConsumptionLogs());

        TokenConsumptionLogResponse response =
                service.consumptionLogs(CURRENT_USER, 1, 20);

        assertEquals(0, response.total());
        verify(newApi).updateAccessToken(
                eq(LOGIN_TOKEN),
                anyString(),
                eq(PAT_ID),
                eq(PAT_NAME),
                eq(UserGatewayTokenService.PAT_SCOPES));
        verify(stateStore).completePatScopeUpgrade(
                eq(USER_ID), anyString(), eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION));
        verify(newApi, never()).deleteAccessToken(anyString(), anyString(), anyLong());
        verify(newApi, never()).createAccessToken(anyString(), anyString(), anyString(), any());
    }

    @Test
    void doesNotUpgradeAPatWhoseScopeVersionIsCurrent() {
        UserGatewayTokenStateStore.Snapshot snapshot = snapshotWithCredentials(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION);
        stubReadySnapshot(snapshot);
        when(newApi.fetchConsumptionLogs(OLD_PAT, 1, 20)).thenReturn(emptyConsumptionLogs());

        service.consumptionLogs(CURRENT_USER, 1, 20);

        verify(stateStore, never()).beginPatScopeUpgrade(
                anyLong(), anyString(), anyInt(), any(), any());
        verify(newApi, never()).updateAccessToken(
                anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void failedScopeUpgradeKeepsTheExistingPatAndReleasesItsLease() {
        UserGatewayTokenStateStore.Snapshot snapshot = snapshotWithCredentials(0);
        stubOldPatUpgrade(snapshot, UserGatewayTokenStateStore.PatScopeUpgradeStatus.ACQUIRED);
        doThrow(new LlmInvocationException("scope update failed"))
                .when(newApi).updateAccessToken(
                        eq(LOGIN_TOKEN),
                        anyString(),
                        eq(PAT_ID),
                        eq(PAT_NAME),
                        any());

        assertThrows(LlmInvocationException.class,
                () -> service.consumptionLogs(CURRENT_USER, 1, 20));

        verify(stateStore).abandonPatScopeUpgrade(eq(USER_ID), anyString());
        verify(stateStore, never()).completePatScopeUpgrade(anyLong(), anyString(), anyInt());
        verify(stateStore, never()).bindPat(
                anyLong(), anyString(), anyLong(), any(), any(), any(), anyInt());
        verify(newApi, never()).deleteAccessToken(anyString(), anyString(), anyLong());
        verify(newApi, never()).fetchConsumptionLogs(anyString(), anyInt(), anyInt());
    }

    @Test
    void replacesAMissingPatBeforeReadingConsumptionLogs() {
        UserGatewayTokenStateStore.Snapshot snapshot = snapshotWithCredentials(0);
        stubOldPatUpgrade(snapshot, UserGatewayTokenStateStore.PatScopeUpgradeStatus.ACQUIRED);
        doThrow(new NewApiHttpException(404, "access token not found"))
                .when(newApi).updateAccessToken(
                        eq(LOGIN_TOKEN),
                        anyString(),
                        eq(PAT_ID),
                        eq(PAT_NAME),
                        any());
        when(newApi.listAccessTokens(LOGIN_TOKEN)).thenReturn(List.of());
        when(newApi.createAccessToken(
                eq(LOGIN_TOKEN), anyString(), eq(PAT_NAME),
                eq(UserGatewayTokenService.PAT_SCOPES)))
                .thenReturn(new NewApiAccessTokenCredential(PAT_ID, NEW_PAT));
        when(newApi.fetchConsumptionLogs(NEW_PAT, 1, 20))
                .thenReturn(emptyConsumptionLogs());

        TokenConsumptionLogResponse response =
                service.consumptionLogs(CURRENT_USER, 1, 20);

        assertEquals(0, response.total());
        verify(stateStore).bindPat(
                eq(USER_ID), anyString(), eq(PAT_ID), any(), any(), any(), eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION));
        verify(stateStore).completePatScopeUpgrade(
                eq(USER_ID), anyString(), eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION));
        verify(newApi).fetchConsumptionLogs(NEW_PAT, 1, 20);
    }

    @Test
    void aBusyScopeUpgradeCanBeRetried() {
        UserGatewayTokenStateStore.Snapshot snapshot = snapshotWithCredentials(0);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, snapshot));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(snapshot));
        stubProvisioningLogin();
        when(stateStore.beginPatScopeUpgrade(
                eq(USER_ID), anyString(), eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION), any(), any()))
                .thenReturn(
                        new UserGatewayTokenStateStore.PatScopeUpgradeClaim(
                                UserGatewayTokenStateStore.PatScopeUpgradeStatus.BUSY,
                                snapshot),
                        new UserGatewayTokenStateStore.PatScopeUpgradeClaim(
                                UserGatewayTokenStateStore.PatScopeUpgradeStatus.ACQUIRED,
                                snapshot));
        when(newApi.fetchConsumptionLogs(OLD_PAT, 1, 20))
                .thenReturn(emptyConsumptionLogs());

        assertThrows(ServiceUnavailableException.class,
                () -> service.consumptionLogs(CURRENT_USER, 1, 20));
        TokenConsumptionLogResponse response =
                service.consumptionLogs(CURRENT_USER, 1, 20);

        assertEquals(0, response.total());
        verify(newApi).updateAccessToken(
                eq(LOGIN_TOKEN),
                anyString(),
                eq(PAT_ID),
                eq(PAT_NAME),
                any());
        verify(newApi).fetchConsumptionLogs(OLD_PAT, 1, 20);
    }

    @Test
    void requireRelayCredentialChecksTheUserBalanceInsteadOfTheRelayTokenQuota() {
        claimReady(RELAY_KEY);
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(
                usageWithTokenAndUserQuota(0, 30, 70));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));
    }

    @Test
    void reusesAStoredPatWhenNewApiAcceptsIt() {
        claimForProvisioning(snapshotWithPat(OLD_PAT));
        stubProvisioningLogin();
        when(newApi.findExactToken(OLD_PAT, TOKEN_NAME, NEW_API_USER_ID)).thenReturn(
                Optional.of(new NewApiToken(RELAY_TOKEN_ID, NEW_API_USER_ID, TOKEN_NAME, true)));
        when(newApi.revealToken(OLD_PAT, RELAY_TOKEN_ID)).thenReturn(RELAY_KEY);
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(100));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));

        verify(newApi).validateAccessToken(OLD_PAT);
        verify(newApi, never()).listAccessTokens(anyString());
        verify(newApi, never()).createAccessToken(anyString(), anyString(), anyString(), any());
        verify(stateStore, never()).bindPat(
                anyLong(), anyString(), anyLong(), any(), any(), any(), anyInt());
    }

    /**
     * A restart empties the password vault, but the PAT is encrypted in the row and usually still
     * valid. Recovering the relay key must then work with no password prompt at all.
     */
    @Test
    void recoversAValidStoredPatWithoutThePasswordAfterARestart() {
        claimForProvisioning(snapshotWithPat(OLD_PAT));
        passwords.forget(USER_ID);
        when(newApi.findExactToken(OLD_PAT, TOKEN_NAME, NEW_API_USER_ID)).thenReturn(
                Optional.of(new NewApiToken(RELAY_TOKEN_ID, NEW_API_USER_ID, TOKEN_NAME, true)));
        when(newApi.revealToken(OLD_PAT, RELAY_TOKEN_ID)).thenReturn(RELAY_KEY);
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(100));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));

        verify(newApi).validateAccessToken(OLD_PAT);
        verify(newApi, never()).login(anyString(), anyString());
    }

    /**
     * First use (no PAT yet) genuinely needs the password: creating an access token requires the
     * user's own credentials. It must surface as 428 — a field the client can supply — not as a
     * dead end, and the lease must be released so the retry is not answered with "still
     * provisioning".
     */
    @Test
    void asksForThePasswordWhenThereIsNoPatYet() {
        claimForProvisioning(snapshotWithoutPat());
        passwords.forget(USER_ID);

        assertThrows(PasswordConfirmationRequiredException.class,
                () -> service.requireRelayCredential(CURRENT_USER));

        verify(newApi, never()).login(anyString(), anyString());
        verify(stateStore).provisioningFailed(
                eq(USER_ID), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void asksForThePasswordWhenTheStoredPatWasRevokedAndTheVaultIsEmpty() {
        claimForProvisioning(snapshotWithPat(OLD_PAT));
        passwords.forget(USER_ID);
        doThrow(new NewApiHttpException(401, "PAT rejected"))
                .when(newApi).validateAccessToken(OLD_PAT);

        assertThrows(PasswordConfirmationRequiredException.class,
                () -> service.requireRelayCredential(CURRENT_USER));

        verify(newApi, never()).login(anyString(), anyString());
        verify(stateStore).provisioningFailed(
                eq(USER_ID), anyString(), anyString(), any(LocalDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void reissuesThePatOnlyWhenNewApiExplicitlyRejectsIt(int status) {
        claimForProvisioning(snapshotWithPat(OLD_PAT));
        stubProvisioningLogin();
        doThrow(new NewApiHttpException(status, "PAT rejected"))
                .when(newApi).validateAccessToken(OLD_PAT);
        when(newApi.listAccessTokens(LOGIN_TOKEN)).thenReturn(
                List.of(new NewApiAccessToken(123L, PAT_NAME)));
        when(newApi.createAccessToken(
                eq(LOGIN_TOKEN), anyString(), eq(PAT_NAME),
                eq(UserGatewayTokenService.PAT_SCOPES)))
                .thenReturn(new NewApiAccessTokenCredential(PAT_ID, NEW_PAT));
        when(newApi.findExactToken(NEW_PAT, TOKEN_NAME, NEW_API_USER_ID)).thenReturn(
                Optional.of(new NewApiToken(RELAY_TOKEN_ID, NEW_API_USER_ID, TOKEN_NAME, true)));
        when(newApi.revealToken(NEW_PAT, RELAY_TOKEN_ID)).thenReturn(RELAY_KEY);
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(100));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));

        verify(newApi).deleteAccessToken(eq(LOGIN_TOKEN), anyString(), eq(123L));
        verify(stateStore).bindPat(
                eq(USER_ID), anyString(), eq(PAT_ID), any(), any(), any(), eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION));
        verify(newApi).findExactToken(NEW_PAT, TOKEN_NAME, NEW_API_USER_ID);
    }

    @Test
    void doesNotReissueThePatForANewApiServerFailure() {
        claimForProvisioning(snapshotWithPat(OLD_PAT));
        stubProvisioningLogin();
        doThrow(new NewApiHttpException(503, "New API is unavailable"))
                .when(newApi).validateAccessToken(OLD_PAT);

        assertThrows(LlmInvocationException.class,
                () -> service.requireRelayCredential(CURRENT_USER));

        verify(newApi, never()).listAccessTokens(anyString());
        verify(newApi, never()).createAccessToken(anyString(), anyString(), anyString(), any());
        verify(stateStore).provisioningFailed(
                eq(USER_ID), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void doesNotReissueThePatForANetworkFailure() {
        claimForProvisioning(snapshotWithPat(OLD_PAT));
        stubProvisioningLogin();
        doThrow(new LlmInvocationException("connection timed out"))
                .when(newApi).validateAccessToken(OLD_PAT);

        assertThrows(LlmInvocationException.class,
                () -> service.requireRelayCredential(CURRENT_USER));

        verify(newApi, never()).listAccessTokens(anyString());
        verify(newApi, never()).createAccessToken(anyString(), anyString(), anyString(), any());
    }

    @Test
    void storesPatAndRelayKeyAsAesGcmCiphertext() {
        claimForProvisioning(snapshotWithoutPat());
        stubProvisioningLogin();
        when(newApi.listAccessTokens(LOGIN_TOKEN)).thenReturn(List.of());
        when(newApi.createAccessToken(
                eq(LOGIN_TOKEN), anyString(), eq(PAT_NAME),
                eq(UserGatewayTokenService.PAT_SCOPES)))
                .thenReturn(new NewApiAccessTokenCredential(PAT_ID, NEW_PAT));
        when(newApi.findExactToken(NEW_PAT, TOKEN_NAME, NEW_API_USER_ID)).thenReturn(
                Optional.of(new NewApiToken(RELAY_TOKEN_ID, NEW_API_USER_ID, TOKEN_NAME, true)));
        when(newApi.revealToken(NEW_PAT, RELAY_TOKEN_ID)).thenReturn(RELAY_KEY);
        when(newApi.fetchUsage(RELAY_KEY)).thenReturn(usage(100));

        assertEquals(RELAY_KEY, service.requireRelayCredential(CURRENT_USER));

        ArgumentCaptor<byte[]> patCiphertext = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> patNonce = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> patTag = ArgumentCaptor.forClass(byte[].class);
        verify(stateStore).bindPat(
                eq(USER_ID),
                anyString(),
                eq(PAT_ID),
                patCiphertext.capture(),
                patNonce.capture(),
                patTag.capture(),
                eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION));
        assertCiphertextDoesNotContainPlaintext(NEW_PAT, patCiphertext.getValue());
        assertEquals(NEW_PAT, cipher.decrypt(
                patCiphertext.getValue(), patNonce.getValue(), patTag.getValue()));

        ArgumentCaptor<byte[]> relayCiphertext = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> relayNonce = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> relayTag = ArgumentCaptor.forClass(byte[].class);
        verify(stateStore).complete(
                eq(USER_ID),
                anyString(),
                eq(RELAY_TOKEN_ID),
                eq(TOKEN_NAME),
                relayCiphertext.capture(),
                relayNonce.capture(),
                relayTag.capture(),
                any(LocalDateTime.class));
        assertCiphertextDoesNotContainPlaintext(RELAY_KEY, relayCiphertext.getValue());
        assertEquals(RELAY_KEY, cipher.decrypt(
                relayCiphertext.getValue(), relayNonce.getValue(), relayTag.getValue()));
    }

    @Test
    void accountCleanupDeletesTheRemoteNewApiAccount() {
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();

        service.cleanupAccountAccess(CURRENT_USER);

        verify(newApi).deleteUser(LOGIN_TOKEN, MIRRORED_PASSWORD);
        // The account is gone, so its keys are gone with it — no separate token calls.
        verify(newApi, never()).deleteRelayToken(anyString(), anyLong());
        verify(newApi, never()).deleteAccessToken(anyString(), anyString(), anyLong());
        verify(stateStore).disableAndClearCredentials(USER_ID);
    }

    @Test
    void accountCleanupRemovesTheRemoteRowWhenAnAdministratorIsConfigured() {
        properties.setAdminUsername("root");
        properties.setAdminPassword("admin-secret");
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();

        service.cleanupAccountAccess(CURRENT_USER);

        // Self-deletion would only mark the row deleted and keep the username reserved, so the
        // administrator path replaces it instead of running after it: both need a step-up
        // verification, and two in a row trip New API's /api/verify rate limit.
        verify(newApi).hardDeleteUser(NEW_API_USER_ID);
        verify(newApi, never()).deleteUser(anyString(), anyString());
    }

    @Test
    void accountCleanupQueuesTheRowWhenTheAdministratorIsRateLimited() {
        properties.setAdminUsername("root");
        properties.setAdminPassword("admin-secret");
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();
        doThrow(new NewApiHttpException(429, "rate limited"))
                .when(newApi).hardDeleteUser(NEW_API_USER_ID);
        when(deletionQueue.enqueue(anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString())).thenReturn(true);

        service.cleanupAccountAccess(CURRENT_USER);

        // 429 不是「不行」而是「现在不行」：这次物理删除必须留在队列里等限流窗口过去，
        // 而不是降级成自助删除（那一步同样要 /api/verify，只会再吃一个 429）。
        verify(deletionQueue).enqueue(
                eq(USER_ID), eq(NEW_API_USER_ID), eq(USERNAME), isNull(),
                eq(NewApiDeletionTaskEntity.MODE_ADMIN), anyString());
        verify(newApi, never()).deleteUser(anyString(), anyString());
        verify(newApi, never()).deleteRelayToken(anyString(), anyLong());
    }

    @Test
    void accountCleanupQueuesThePasswordWhenSelfDeletionIsRateLimited() {
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();
        doThrow(new NewApiHttpException(429, "rate limited"))
                .when(newApi).deleteUser(LOGIN_TOKEN, MIRRORED_PASSWORD);
        when(deletionQueue.enqueue(anyLong(), any(), anyString(), anyString(), anyString(),
                anyString())).thenReturn(true);

        service.cleanupAccountAccess(CURRENT_USER);

        // 自助路径重放时只认密码，而本地会话里的那份马上就会被清掉，所以必须跟着任务一起存。
        verify(deletionQueue).enqueue(
                eq(USER_ID), eq(NEW_API_USER_ID), eq(USERNAME), eq(MIRRORED_PASSWORD),
                eq(NewApiDeletionTaskEntity.MODE_SELF), anyString());
        verify(newApi, never()).deleteRelayToken(anyString(), anyLong());
    }

    @Test
    void accountCleanupFallsBackToSelfDeletionWhenTheRowCannotBeRemoved() {
        properties.setAdminUsername("root");
        properties.setAdminPassword("admin-secret");
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();
        doThrow(new NewApiHttpException(403, "insufficient privileges"))
                .when(newApi).hardDeleteUser(NEW_API_USER_ID);

        // A refusal from the administrator path must not leave the account alive: the ordinary
        // self-deletion still removes it, only the username stays reserved.
        service.cleanupAccountAccess(CURRENT_USER);

        verify(newApi).hardDeleteUser(NEW_API_USER_ID);
        verify(newApi).deleteUser(LOGIN_TOKEN, MIRRORED_PASSWORD);
    }

    @Test
    void accountCleanupLeavesTheRowWhenNoAdministratorIsConfigured() {
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();

        service.cleanupAccountAccess(CURRENT_USER);

        // Default configuration: the account is deleted, its username stays reserved.
        verify(newApi, never()).hardDeleteUser(anyLong());
        verify(newApi).deleteUser(LOGIN_TOKEN, MIRRORED_PASSWORD);
    }


    @Test
    void accountCleanupFallsBackToKeysWhenTheRemoteAccountCannotBeDeleted() {
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();
        doThrow(new NewApiHttpException(403, "Cannot delete super administrator account"))
                .when(newApi).deleteUser(LOGIN_TOKEN, MIRRORED_PASSWORD);

        service.cleanupAccountAccess(CURRENT_USER);

        // Upstream refused, so the local account must still not leave usable credentials behind.
        verify(newApi).deleteRelayToken(OLD_PAT, RELAY_TOKEN_ID);
        verify(newApi).deleteAccessToken(eq(LOGIN_TOKEN), anyString(), eq(PAT_ID));
        verify(stateStore).disableAndClearCredentials(USER_ID);
    }

    @Test
    void accountCleanupSkipsTheRemoteAccountWhenThePasswordIsNoLongerHeld() {
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();
        passwords.forget(USER_ID);

        service.cleanupAccountAccess(CURRENT_USER);

        // Without the plaintext password New API cannot be asked to delete the account.
        verify(newApi, never()).deleteUser(anyString(), anyString());
        verify(newApi).deleteRelayToken(OLD_PAT, RELAY_TOKEN_ID);
    }

    @Test
    void accountCleanupContinuesAfterNewApiFailures() {
        UserGatewayTokenStateStore.Snapshot credentials = snapshotWithCredentials();
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(credentials));
        stubProvisioningLogin();
        doThrow(new LlmInvocationException("account deletion failed"))
                .when(newApi).deleteUser(anyString(), anyString());
        doThrow(new LlmInvocationException("relay token deletion failed"))
                .when(newApi).deleteRelayToken(OLD_PAT, RELAY_TOKEN_ID);
        doThrow(new LlmInvocationException("PAT deletion failed"))
                .when(newApi).deleteAccessToken(eq(LOGIN_TOKEN), anyString(), eq(PAT_ID));

        service.cleanupAccountAccess(CURRENT_USER);

        verify(stateStore).disableAndClearCredentials(USER_ID);
    }

    /**
     * 管理台删人时本地行已经删掉了，绑定行随之 CASCADE，所以远端 id 是调用方先读出来传进来的。
     * 这条路径上没有任何用户密码可用 —— 只有管理员身份能删远端。
     */
    @Test
    void adminDeletionRemovesTheRemoteRowWithTheAdministrator() {
        properties.setAdminUsername("root");
        properties.setAdminPassword("admin-secret");
        passwords.forget(USER_ID);

        RemoteAccountCleanup cleanup =
                service.deleteRemoteAccountOfDeletedUser(USER_ID, USERNAME, NEW_API_USER_ID);

        assertEquals(RemoteAccountCleanup.DELETED, cleanup);
        verify(newApi).hardDeleteUser(NEW_API_USER_ID);
        // 没有密码，就不该去猜着登录：那条路要么 428，要么撞上别人的账号。
        verify(newApi, never()).login(anyString(), anyString());
    }

    /** 没配管理员又没有密码时，远端那一行只能留下 —— 但必须如实回报，不能装作删干净了。 */
    @Test
    void adminDeletionReportsTheRowItCannotReach() {
        passwords.forget(USER_ID);

        RemoteAccountCleanup cleanup =
                service.deleteRemoteAccountOfDeletedUser(USER_ID, USERNAME, NEW_API_USER_ID);

        assertEquals(RemoteAccountCleanup.LEFT_IN_PLACE, cleanup);
        verify(newApi, never()).hardDeleteUser(anyLong());
        verify(newApi, never()).deleteUser(anyString(), anyString());
    }

    /** 从来没绑过远端身份的本地账号：不适用，而不是「没删掉」。 */
    @Test
    void adminDeletionIgnoresAccountsWithoutARemoteIdentity() {
        RemoteAccountCleanup cleanup =
                service.deleteRemoteAccountOfDeletedUser(USER_ID, USERNAME, null);

        assertEquals(RemoteAccountCleanup.NOT_APPLICABLE, cleanup);
        verify(newApi, never()).hardDeleteUser(anyLong());
        verify(newApi, never()).deleteUser(anyString(), anyString());
    }

    /** 排队也算交代出去了：worker 会接着删，管理台不该为此报「没删掉」。 */
    @Test
    void adminDeletionReportsQueuedRetriesAsResolved() {
        properties.setAdminUsername("root");
        properties.setAdminPassword("admin-secret");
        passwords.forget(USER_ID);
        doThrow(new NewApiHttpException(429, "rate limited"))
                .when(newApi).hardDeleteUser(NEW_API_USER_ID);
        when(deletionQueue.enqueue(anyLong(), anyLong(), anyString(), any(), anyString(),
                anyString())).thenReturn(true);

        RemoteAccountCleanup cleanup =
                service.deleteRemoteAccountOfDeletedUser(USER_ID, USERNAME, NEW_API_USER_ID);

        assertEquals(RemoteAccountCleanup.QUEUED, cleanup);
        assertTrue(cleanup.isResolved(), "已经排队的删除不该再让管理台提示「未删除」");
    }

    private void claimReady(String relayKey) {
        UserGatewayTokenStateStore.Snapshot ready = readySnapshot(relayKey, 100);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, ready));
    }

    private void claimForProvisioning(UserGatewayTokenStateStore.Snapshot snapshot) {
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.ACQUIRED, snapshot));
    }

    private void stubReadySnapshot(UserGatewayTokenStateStore.Snapshot snapshot) {
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.Claim(
                        UserGatewayTokenStateStore.ClaimStatus.READY, snapshot));
        when(stateStore.find(USER_ID)).thenReturn(Optional.of(snapshot));
    }

    private void stubOldPatUpgrade(
            UserGatewayTokenStateStore.Snapshot snapshot,
            UserGatewayTokenStateStore.PatScopeUpgradeStatus status) {
        stubReadySnapshot(snapshot);
        stubProvisioningLogin();
        when(stateStore.beginPatScopeUpgrade(
                eq(USER_ID), anyString(), eq(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION), any(), any()))
                .thenReturn(new UserGatewayTokenStateStore.PatScopeUpgradeClaim(
                        status, snapshot));
    }

    private static NewApiConsumptionLogPage emptyConsumptionLogs() {
        return new NewApiConsumptionLogPage(1, 20, 0, List.of());
    }

    private void stubProvisioningLogin() {
        when(newApi.login(eq(USERNAME), eq(MIRRORED_PASSWORD)))
                .thenReturn(new NewApiLoginSession(
                        NEW_API_USER_ID, USERNAME, LOGIN_TOKEN));
    }

    private UserGatewayTokenStateStore.Snapshot snapshotWithPat(String pat) {
        AesGcmTokenCipher.EncryptedToken encryptedPat = cipher.encrypt(pat);
        return snapshot(
                encryptedPat.ciphertext(), encryptedPat.nonce(), encryptedPat.tag(),
                null, null, null, 100);
    }

    private UserGatewayTokenStateStore.Snapshot snapshotWithCredentials() {
        return snapshotWithCredentials(UserGatewayTokenService.CURRENT_PAT_SCOPE_VERSION);
    }

    private UserGatewayTokenStateStore.Snapshot snapshotWithCredentials(int scopeVersion) {
        AesGcmTokenCipher.EncryptedToken encryptedPat = cipher.encrypt(OLD_PAT);
        AesGcmTokenCipher.EncryptedToken encryptedRelayKey = cipher.encrypt(RELAY_KEY);
        return snapshot(
                encryptedPat.ciphertext(),
                encryptedPat.nonce(),
                encryptedPat.tag(),
                encryptedRelayKey.ciphertext(),
                encryptedRelayKey.nonce(),
                encryptedRelayKey.tag(),
                scopeVersion,
                100);
    }

    private UserGatewayTokenStateStore.Snapshot snapshotWithoutPat() {
        return snapshot(null, null, null, null, null, null, 100);
    }

    private UserGatewayTokenStateStore.Snapshot readySnapshot(String relayKey, long available) {
        AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(relayKey);
        return snapshot(
                null, null, null,
                encrypted.ciphertext(), encrypted.nonce(), encrypted.tag(), available);
    }

    private UserGatewayTokenStateStore.Snapshot readySnapshot(
            String relayKey, long totalGranted, long totalUsed, long totalAvailable) {
        AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(relayKey);
        return snapshot(
                null,
                null,
                null,
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.tag(),
                totalGranted,
                totalUsed,
                totalAvailable);
    }

    private static UserGatewayTokenStateStore.Snapshot snapshot(
            byte[] patCiphertext,
            byte[] patNonce,
            byte[] patTag,
            byte[] keyCiphertext,
            byte[] keyNonce,
            byte[] keyTag,
            long available) {
        return snapshot(
                patCiphertext,
                patNonce,
                patTag,
                keyCiphertext,
                keyNonce,
                keyTag,
                100,
                100 - available,
                available);
    }

    private static UserGatewayTokenStateStore.Snapshot snapshot(
            byte[] patCiphertext,
            byte[] patNonce,
            byte[] patTag,
            byte[] keyCiphertext,
            byte[] keyNonce,
            byte[] keyTag,
            long totalGranted,
            long totalUsed,
            long totalAvailable) {
        return snapshot(
                patCiphertext,
                patNonce,
                patTag,
                keyCiphertext,
                keyNonce,
                keyTag,
                patCiphertext == null ? 0 : 1,
                totalGranted,
                totalUsed,
                totalAvailable);
    }

    private static UserGatewayTokenStateStore.Snapshot snapshot(
            byte[] patCiphertext,
            byte[] patNonce,
            byte[] patTag,
            byte[] keyCiphertext,
            byte[] keyNonce,
            byte[] keyTag,
            int patScopeVersion,
            long available) {
        return snapshot(
                patCiphertext,
                patNonce,
                patTag,
                keyCiphertext,
                keyNonce,
                keyTag,
                patScopeVersion,
                100,
                100 - available,
                available);
    }

    /** 可指定同步时间的就绪快照，用来验证「快照是否过期」这条分支。 */
    private UserGatewayTokenStateStore.Snapshot snapshotWithSyncAt(LocalDateTime syncedAt) {
        return snapshotWithSyncAt(syncedAt, false, 70);
    }

    private UserGatewayTokenStateStore.Snapshot snapshotWithSyncAt(
            LocalDateTime syncedAt, boolean unlimited, long available) {
        AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(RELAY_KEY);
        return new UserGatewayTokenStateStore.Snapshot(
                1L,
                USER_ID,
                SITE_ID,
                NEW_API_USER_ID,
                RELAY_TOKEN_ID,
                PAT_ID,
                null,
                null,
                null,
                1,
                TOKEN_NAME,
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.tag(),
                "READY",
                null,
                null,
                1,
                100,
                100 - available,
                available,
                unlimited,
                "{}",
                false,
                null,
                syncedAt);
    }

    private static UserGatewayTokenStateStore.Snapshot snapshot(
            byte[] patCiphertext,
            byte[] patNonce,
            byte[] patTag,
            byte[] keyCiphertext,
            byte[] keyNonce,
            byte[] keyTag,
            int patScopeVersion,
            long totalGranted,
            long totalUsed,
            long totalAvailable) {
        return new UserGatewayTokenStateStore.Snapshot(
                1L,
                USER_ID,
                SITE_ID,
                NEW_API_USER_ID,
                keyCiphertext == null ? null : RELAY_TOKEN_ID,
                patCiphertext == null ? null : PAT_ID,
                patCiphertext,
                patNonce,
                patTag,
                patScopeVersion,
                TOKEN_NAME,
                keyCiphertext,
                keyNonce,
                keyTag,
                keyCiphertext == null ? "PROVISIONING" : "READY",
                null,
                null,
                1,
                totalGranted,
                totalUsed,
                totalAvailable,
                false,
                "{}",
                false,
                null,
                // 故意早于固定时钟（2026-10-20T00:00:00Z）超过 usageCacheTtl，
                // 让默认路径走「快照过期 → 查上游」，缓存命中另有用例覆盖。
                LocalDateTime.parse("2026-10-19T00:00:00"));
    }

    private static NewApiTokenUsage usage(long userAvailable) {
        return usageWithTokenAndUserQuota(userAvailable, 100 - userAvailable, userAvailable);
    }

    private static NewApiTokenUsage usageWithTokenAndUserQuota(
            long tokenAvailable, long userUsed, long userAvailable) {
        return new NewApiTokenUsage(
                TOKEN_NAME,
                100,
                100 - tokenAvailable,
                tokenAvailable,
                false,
                new ObjectMapper().createObjectNode(),
                false,
                0,
                userUsed,
                userAvailable);
    }

    private static void assertCiphertextDoesNotContainPlaintext(String plaintext, byte[] ciphertext) {
        assertFalse(Arrays.equals(
                        plaintext.getBytes(StandardCharsets.UTF_8), ciphertext),
                "secret must not be stored as plaintext bytes");
    }
}

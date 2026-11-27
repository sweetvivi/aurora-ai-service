package com.hub.backend.modules.gateway;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiToken;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.crypto.AesGcmTokenCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewApiGroupTokenServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long SITE_ID = 1L;
    private static final long NEW_API_USER_ID = 7L;
    private static final CurrentUser USER = new CurrentUser(USER_ID, SITE_ID, "demo");

    private NewApiGroupTokenStateStore stateStore;
    private NewApiManagementClient newApi;
    private UserGatewayTokenService baseTokens;
    private AesGcmTokenCipher cipher;
    private NewApiGroupTokenService service;

    @BeforeEach
    void setUp() {
        stateStore = mock(NewApiGroupTokenStateStore.class);
        newApi = mock(NewApiManagementClient.class);
        baseTokens = mock(UserGatewayTokenService.class);

        NewApiProperties properties = new NewApiProperties();
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new AesGcmTokenCipher(properties);
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);

        when(baseTokens.relayTokenPrefix()).thenReturn("hub");
        when(baseTokens.requireRelayBase(USER))
                .thenReturn(new UserGatewayTokenService.RelayBase("pat-value", NEW_API_USER_ID));

        service = new NewApiGroupTokenService(
                stateStore, newApi, baseTokens, properties, cipher, clock);
    }

    @Test
    void createsTheGroupTokenOnceAndReturnsItsKey() {
        when(newApi.fetchUserGroupNames("pat-value")).thenReturn(List.of("default", "kimi"));
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.ACQUIRED,
                        snapshot(NewApiGroupTokenStateStore.ClaimStatus.ACQUIRED.name())));
        when(newApi.findExactToken(anyString(), anyString(), eq(NEW_API_USER_ID)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new NewApiToken(99L, NEW_API_USER_ID, "hub-1-u42-gdeadbeef", true)));
        when(newApi.revealToken("pat-value", 99L)).thenReturn("sk-group-key");

        assertEquals("sk-group-key", service.requireGroupKey(USER, "kimi"));
        verify(newApi).createRelayToken(eq("pat-value"), anyString(), eq("kimi"));
        verify(stateStore).complete(
                eq(USER_ID), eq("kimi"), anyString(), eq(99L), any(), any(), any());
    }

    @Test
    void reusesAStoredKeyWithoutTalkingToNewApi() {
        String expected = "sk-already-there";
        AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(expected);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.READY,
                        snapshot("ACTIVE", 99L, encrypted)));

        assertEquals(expected, service.requireGroupKey(USER, "kimi"));
        verify(newApi, never()).createRelayToken(anyString(), anyString(), anyString());
    }

    @Test
    void refusesTheReservedAutoGroup() {
        ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                () -> service.requireGroupKey(USER, "auto"));
        assertTrue(failure.getMessage().contains("auto"));
        verify(newApi, never()).createRelayToken(anyString(), anyString(), anyString());
    }

    @Test
    void refusesAGroupTheAccountCannotUse() {
        when(newApi.fetchUserGroupNames("pat-value")).thenReturn(List.of("default"));
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.ACQUIRED,
                        snapshot(NewApiGroupTokenStateStore.ClaimStatus.ACQUIRED.name())));

        assertThrows(ServiceUnavailableException.class,
                () -> service.requireGroupKey(USER, "vip"));
        verify(newApi, never()).createRelayToken(anyString(), anyString(), anyString());
    }

    @Test
    void derivesATokenNameThatFitsTheNewApiLimit() {
        // 中文分组名可以到 64 字符，直接拼进去会超过 New API 的 50 字符上限。
        String longChineseGroup = "视频分组-" + "超长名字".repeat(10);
        when(newApi.fetchUserGroupNames("pat-value")).thenReturn(List.of(longChineseGroup));
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.ACQUIRED,
                        snapshot(NewApiGroupTokenStateStore.ClaimStatus.ACQUIRED.name())));
        when(newApi.findExactToken(anyString(), anyString(), eq(NEW_API_USER_ID)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new NewApiToken(99L, NEW_API_USER_ID, "hub-1-u42-gabcdef01", true)));
        when(newApi.revealToken("pat-value", 99L)).thenReturn("sk-group-key");

        service.requireGroupKey(USER, longChineseGroup);

        // 同一个分组永远得到同一个名字，于是本地行丢了也能按名字找回上游那把钥匙。
        org.mockito.ArgumentCaptor<String> names = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(newApi, times(2)).findExactToken(anyString(), names.capture(), eq(NEW_API_USER_ID));
        assertTrue(names.getAllValues().get(0).length() <= 50, names.getAllValues().get(0));
        assertEquals(names.getAllValues().get(0), names.getAllValues().get(1));
    }

    @Test
    void reportsBusyWhileAnotherRequestIsProvisioning() {
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.BUSY,
                        snapshot(NewApiGroupTokenStateStore.ClaimStatus.BUSY.name())));

        assertThrows(ServiceUnavailableException.class, () -> service.requireGroupKey(USER, "kimi"));
    }

    @Test
    void waitsForTheConcurrentProvisioningInsteadOfFailingTheFirstBatch() {
        String expected = "sk-built-by-the-other-request";
        AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(expected);
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new NewApiGroupTokenStateStore.Claim(
                                NewApiGroupTokenStateStore.ClaimStatus.BUSY,
                                snapshot(NewApiGroupTokenStateStore.ClaimStatus.BUSY.name())))
                .thenReturn(new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.READY,
                        snapshot("ACTIVE", 99L, encrypted)));

        // 出图页一次发几个请求时，后来者应当等前一个建好并复用同一把钥匙。
        assertEquals(expected, service.requireGroupKey(USER, "kimi"));
        verify(newApi, never()).createRelayToken(anyString(), anyString(), anyString());
    }

    @Test
    void surfacesTheRealErrorInsteadOfClaimingItIsStillProvisioning() {
        when(stateStore.claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new NewApiGroupTokenStateStore.Claim(
                        NewApiGroupTokenStateStore.ClaimStatus.BACKOFF,
                        snapshotWithError("ERROR", "403 No permission to access group 图片生成")));

        ServiceUnavailableException failure = assertThrows(ServiceUnavailableException.class,
                () -> service.requireGroupKey(USER, "图片生成"));
        // 退避窗口内必须带出上次的真实原因，否则永久性失败会被当成「正在开通」。
        assertTrue(failure.getMessage().contains("403 No permission to access group 图片生成"),
                failure.getMessage());
        verify(newApi, never()).createRelayToken(anyString(), anyString(), anyString());
    }

    private static NewApiGroupTokenStateStore.Snapshot snapshot(String status) {
        return snapshot(status, null, null);
    }

    private static NewApiGroupTokenStateStore.Snapshot snapshotWithError(String status, String lastError) {
        return new NewApiGroupTokenStateStore.Snapshot(
                1L, USER_ID, SITE_ID, "图片生成", "hub-1-u42-gdeadbeef", null,
                null, null, null, status, lastError);
    }

    private static NewApiGroupTokenStateStore.Snapshot snapshot(
            String status, Long tokenId, AesGcmTokenCipher.EncryptedToken encrypted) {
        return new NewApiGroupTokenStateStore.Snapshot(
                1L, USER_ID, SITE_ID, "kimi", "hub-1-u42-gdeadbeef", tokenId,
                encrypted == null ? null : encrypted.ciphertext(),
                encrypted == null ? null : encrypted.nonce(),
                encrypted == null ? null : encrypted.tag(),
                status, null);
    }
}

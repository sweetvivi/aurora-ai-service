package com.hub.backend.modules.thesis;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.thesis.repository.ThesisDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Full-text billing gate.
 *
 * <p>正文与大纲、对话共用 New API 统一计费：每次生成都必须先确定「谁付钱」。按账号计费时
 * 凭据是调用者自己的中继令牌（游客没有，因此开流前就 401），本地关闭中继时退回静态 key
 * （没配 key 就是 503）。这三种情况构成这条规则的完整边界。</p>
 */
class ThesisFullTextBillingTest {

    private static final CurrentUser USER = new CurrentUser(42L, 1L, "demo");

    private final LlmProperties llmProperties = new LlmProperties();
    private final NewApiProperties newApiProperties = new NewApiProperties();
    private final UserGatewayTokenService tokens = mock(UserGatewayTokenService.class);
    private final UserRelayCredentialResolver relayCredentials =
            new UserRelayCredentialResolver(
                    tokens,
                    mock(com.hub.backend.modules.gateway.NewApiGroupTokenService.class),
                    mock(com.hub.backend.modules.model.ModelGroupRouter.class),
                    llmProperties,
                    newApiProperties);

    private final ThesisFullTextGenerator generator = new ThesisFullTextGenerator(
            mock(LlmClient.class), llmProperties, newApiProperties, relayCredentials,
            new com.hub.backend.modules.model.ModelUseResolver(
                    mock(com.hub.backend.modules.model.repository.LlmModelRepository.class),
                    new ObjectMapper(),
                    mock(com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository.class),
                    llmProperties));

    private final ThesisService service = new ThesisService(
            mock(ThesisDocumentRepository.class),
            mock(ThesisOutlineGenerator.class),
            mock(ThesisModelOutlineGenerator.class),
            mock(ThesisOutlineNormalizer.class),
            mock(ThesisOutlineCodec.class),
            mock(ThesisCatalogService.class),
            generator,
            mock(ThesisReferenceService.class),
            new ObjectMapper());

    @Test
    void guestIsRejectedWhenRelayBillingIsOn() {
        newApiProperties.setEnabled(true);

        UnauthorizedException exception = assertThrows(UnauthorizedException.class,
                () -> generator.checkBillable(null));

        assertTrue(exception.getMessage().contains("登录"), exception.getMessage());
        verifyNoInteractions(tokens);
    }

    @Test
    void signedInCallerIsBilledThroughTheirOwnRelayToken() {
        newApiProperties.setEnabled(true);
        // requireRelayCredential 内部已完成开通、取余额与 402 判断；这里只验证凭据来源是「这个人」。
        when(tokens.requireRelayCredential(USER)).thenReturn("sk-user-42");

        generator.checkBillable(USER);

        verify(tokens).requireRelayCredential(USER);
    }

    @Test
    void relayDisabledFallsBackToTheConfiguredStaticKey() {
        newApiProperties.setEnabled(false);
        llmProperties.setApiKey("sk-static-dev");

        generator.checkBillable(null);

        verifyNoInteractions(tokens);
    }

    @Test
    void relayDisabledWithoutStaticKeyReportsServiceUnavailable() {
        newApiProperties.setEnabled(false);
        llmProperties.setApiKey("  ");

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
                () -> generator.checkBillable(null));

        assertEquals("app.llm.api-key is required when app.new-api.enabled=false",
                exception.getMessage());
    }

    @Test
    void zeroBalanceIsReportedInWordsTheUserCanActOn() {
        newApiProperties.setEnabled(true);
        when(tokens.requireRelayCredential(USER)).thenThrow(new PaymentRequiredException(
                "Insufficient account balance. Top up the New API account and retry"));

        PaymentRequiredException exception = assertThrows(PaymentRequiredException.class,
                () -> service.checkFullTextBilling(USER));

        assertTrue(exception.getMessage().contains("余额不足"), exception.getMessage());
        assertFalse(exception.getMessage().contains("Insufficient"), exception.getMessage());
    }
}

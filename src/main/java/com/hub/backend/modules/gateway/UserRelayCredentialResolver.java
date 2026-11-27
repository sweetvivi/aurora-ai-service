package com.hub.backend.modules.gateway;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.model.ModelGroupRouter;
import org.springframework.stereotype.Component;

/**
 * Resolves the upstream credentials for one user's AI call.
 *
 * <p>By default every user AI call is routed through the shared New API relay host with that
 * user's own relay key, so usage is billed to the caller.</p>
 *
 * <p>The key is group-scoped, not just user-scoped: New API picks a channel by the token's
 * {@code group}, so a model that only exists in another group needs a token for that group. The
 * overloads that take a model id do exactly that — they resolve the model's groups
 * ({@link ModelGroupRouter}) and provision a per-group token on first use. Without a model id the
 * account token is used, which is the historical behaviour.</p>
 *
 * <p>When {@code app.new-api.enabled} is false the relay is skipped and the static
 * {@code app.llm.api-key} is used instead. That is a local development escape hatch for
 * environments without New API management credentials: without it the relay key can never be
 * provisioned and every chat turn fails with 503. It has to stay on in any billed environment,
 * because otherwise every caller shares one key and usage stops being attributable.</p>
 */
@Component
public class UserRelayCredentialResolver {

    private final UserGatewayTokenService tokens;
    private final NewApiGroupTokenService groupTokens;
    private final ModelGroupRouter groupRouter;
    private final LlmProperties llmProperties;
    private final NewApiProperties newApiProperties;

    public UserRelayCredentialResolver(UserGatewayTokenService tokens,
                                       NewApiGroupTokenService groupTokens,
                                       ModelGroupRouter groupRouter,
                                       LlmProperties llmProperties,
                                       NewApiProperties newApiProperties) {
        this.tokens = tokens;
        this.groupTokens = groupTokens;
        this.groupRouter = groupRouter;
        this.llmProperties = llmProperties;
        this.newApiProperties = newApiProperties;
    }

    public ProviderCredentials resolve(CurrentUser user) {
        return resolve(user, null);
    }

    /**
     * @param modelId 目录行 id（{@code llm_models.id}）或 {@code null}；给定后按模型所属分组取钥匙
     */
    public ProviderCredentials resolve(CurrentUser user, Long modelId) {
        return credentials(relayKey(user, modelId, true, llmProperties.getApiKey()));
    }

    /**
     * Credentials for background work that outlives the caller's request, such as polling a
     * submitted job every few seconds.
     *
     * <p>Identical to {@link #resolve(CurrentUser, Long)} except that it does not ask New API for
     * the account balance. A poll cycle must not turn into a billing round-trip, and it does not
     * need to: the job could not have been submitted without passing that check in the first
     * place.</p>
     */
    public ProviderCredentials resolveKeyOnly(CurrentUser user) {
        return resolveKeyOnly(user, null);
    }

    public ProviderCredentials resolveKeyOnly(CurrentUser user, Long modelId) {
        return credentials(relayKey(user, modelId, false, llmProperties.getApiKey()));
    }

    /**
     * Credentials for image endpoints, which may live on a differently scoped upstream key.
     * Only the relay-disabled (local development) path differs; relay keys are per-group and
     * work for both chat and image models.
     */
    public ProviderCredentials resolveImage(CurrentUser user) {
        return resolveImage(user, null);
    }

    public ProviderCredentials resolveImage(CurrentUser user, Long modelId) {
        return credentials(relayKey(user, modelId, true, llmProperties.resolveImageApiKey()));
    }

    /**
     * 平台侧凭据：费用记平台，不查、不扣终端用户余额。
     *
     * <p>用于站内客服等能力；基地址与密钥均由管理端配置后传入，不再依赖环境变量写死。</p>
     */
    public ProviderCredentials resolvePlatform(String baseUrl, String apiKey) {
        String key = staticApiKey(apiKey, "客服平台 API Key");
        String url = staticApiKey(baseUrl, "客服请求基地址");
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                url,
                key,
                null,
                null,
                null);
    }

    /**
     * 账号令牌永远是额度与账单的判据，所以先过它（含余额检查），再按需换成模型所属分组的钥匙。
     *
     * @param fallbackKey relay 关闭时使用（本地开发）
     */
    private String relayKey(CurrentUser user, Long modelId, boolean checkBalance,
                            String fallbackKey) {
        if (!newApiProperties.isEnabled()) {
            return staticApiKey(fallbackKey, "app.llm.api-key");
        }
        String accountKey = checkBalance
                ? tokens.requireRelayCredential(user)
                : tokens.requireRelayKey(user);
        String group = groupForModel(modelId);
        if (group == null) {
            return accountKey;
        }
        return groupTokens.requireGroupKey(user, group);
    }

    /**
     * 模型所属的「非默认」分组；默认分组的模型直接用账号令牌，与升级前完全一致。
     *
     * @return {@code null} 表示沿用账号令牌
     */
    private String groupForModel(Long modelId) {
        if (modelId == null) {
            return null;
        }
        String configured = newApiProperties.getGroup() == null
                || newApiProperties.getGroup().isBlank()
                ? "default"
                : newApiProperties.getGroup().strip();
        String group = groupRouter.preferredGroup(modelId, configured);
        if (group == null || group.equals(configured)) {
            return null;
        }
        return group;
    }

    private ProviderCredentials credentials(String apiKey) {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                llmProperties.resolvedBaseUrl(),
                apiKey,
                null,
                null,
                null);
    }

    private String staticApiKey(String configured, String propertyName) {
        if (configured == null || configured.isBlank()) {
            throw new ServiceUnavailableException(propertyName + " 未配置或为空");
        }
        return configured;
    }
}

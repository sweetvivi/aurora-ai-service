package com.hub.backend.modules.model.provider;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.infra.llm.ProviderCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Turns a model's {@code provider_id} into the credentials an upstream call should use.
 *
 * <p>Resolution order for the key:</p>
 * <ol>
 *   <li>{@code llm_providers.api_key} — what an operator typed into the admin console;</li>
 *   <li>{@code llm_providers.credential_ref} when it is an {@code env:NAME} reference;</li>
 *   <li>{@code null}, which tells the adapter to fall back to {@code app.llm.*} configuration.</li>
 * </ol>
 *
 * <p>Returning {@code null} for "nothing configured here" is deliberate: it keeps the existing
 * configuration working unchanged, so pointing a model at a supplier is an opt-in that can be
 * rolled back per model.</p>
 *
 * <p>User-facing chat / image / video traffic does not use this resolver for its key: those calls go
 * through {@code UserRelayCredentialResolver} and the caller's New API relay key. The video path
 * does read it for one thing — where a finished file is downloaded from and with which key,
 * declared as {@code video_download_base_url} and {@code video_download_api_key}. This class also
 * serves catalogue sync and other platform-owned hops that still point at a supplier row.</p>
 */
@Component
public class ProviderCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialResolver.class);

    private static final String ENV_PREFIX = "env:";

    private final LlmProviderRepository providers;
    private final LlmProperties llmProperties;

    public ProviderCredentialResolver(LlmProviderRepository providers, LlmProperties llmProperties) {
        this.providers = providers;
        this.llmProperties = llmProperties;
    }

    /**
     * @param providerId supplier of the model, may be {@code null}
     * @return credentials to use, or {@code null} when the service defaults should apply
     */
    public ProviderCredentials resolve(Long providerId) {
        if (providerId == null) {
            return null;
        }
        return providers.findById(providerId)
                .filter(LlmProviderEntity::isActive)
                .map(this::toCredentials)
                .orElse(null);
    }

    private ProviderCredentials toCredentials(LlmProviderEntity provider) {
        String key = resolveKey(provider);
        // A declared download origin means the finished file is fetched from the supplier rather
        // than from the relay, and the caller's relay key is not valid on that host. The download
        // key is resolved separately so the row can hold one key for the relay and another for the
        // supplier origin, which is the normal shape when the row is a synced relay channel.
        String downloadBase = provider.getVideoDownloadBaseUrl();
        return new ProviderCredentials(
                provider.getProtocol(),
                provider.getBaseUrl(),
                key,
                provider.getEndpointPath(),
                // null keeps the adapter on app.llm.images-path, so a supplier that shares the
                // gateway default needs no value here.
                provider.getImagesPath(),
                provider.getApiVersion(),
                null,
                null,
                downloadBase,
                downloadBase == null ? null : downloadKeyFor(provider, key));
    }

    /**
     * Key used on the supplier origin.
     *
     * <p>Resolution order, and why it is this order:</p>
     * <ol>
     *   <li>{@code llm_providers.video_download_api_key} — the column that belongs to the origin in
     *       {@code video_download_base_url}. It is the only place a key can live and survive: the
     *       catalogue sync rewrites the relay-owned row on every refresh and clears {@code
     *       api_key}, so a key stored there works until the next sync and then silently falls back
     *       to the relay key, which the supplier host rejects. Both the origin and its key are now
     *       ordinary row data, so moving to another supplier is a database update rather than a
     *       redeploy.</li>
     *   <li>The submit key resolved for this row — for a self-hosted supplier the same credential
     *       usually serves both hops, and rotating one rotates both.</li>
     *   <li>{@code app.llm.video-download-api-key} — kept last so a deployment that already
     *       configured the environment variable keeps behaving exactly as before.</li>
     * </ol>
     *
     * @return the download key, or {@code null} which leaves the relay key in charge
     */
    private String downloadKeyFor(LlmProviderEntity provider, String rowKey) {
        if (provider.hasVideoDownloadApiKey()) {
            return provider.getVideoDownloadApiKey();
        }
        if (rowKey != null && !rowKey.isBlank()) {
            return rowKey;
        }
        String configured = llmProperties.getVideoDownloadApiKey();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return null;
    }

    private String resolveKey(LlmProviderEntity provider) {
        if (provider.hasApiKey()) {
            return provider.getApiKey();
        }

        String reference = provider.getCredentialRef();
        if (reference == null || reference.isBlank()) {
            return null;
        }
        if (reference.toLowerCase(Locale.ROOT).startsWith(ENV_PREFIX)) {
            String name = reference.substring(ENV_PREFIX.length()).strip();
            String value = name.isEmpty() ? null : System.getenv(name);
            if (value == null || value.isBlank()) {
                log.debug("Provider '{}' references environment variable '{}', which is not set",
                        provider.getProviderKey(), name);
                return null;
            }
            return value;
        }

        // Anything else (for example 'config:app.llm.api-key') means "use the service default".
        return null;
    }
}

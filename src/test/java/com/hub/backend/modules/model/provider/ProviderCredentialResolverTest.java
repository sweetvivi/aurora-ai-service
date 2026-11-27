package com.hub.backend.modules.model.provider;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.infra.llm.ProviderCredentials;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderCredentialResolverTest {

    private static LlmProviderEntity provider(String downloadBase) {
        LlmProviderEntity provider = new LlmProviderEntity(
                "xai-video",
                "xAI Video",
                "OPENAI_COMPATIBLE",
                "http://127.0.0.1:3000/v1",
                null,
                null,
                "BEARER",
                "config:new-api-user-relay",
                LlmProviderEntity.STATUS_ACTIVE,
                0);
        provider.setApiKey("sk-supplier");
        provider.setVideoDownloadBaseUrl(downloadBase);
        return provider;
    }

    private static ProviderCredentialResolver resolverFor(LlmProviderEntity provider) {
        return resolverFor(provider, "");
    }

    private static ProviderCredentialResolver resolverFor(LlmProviderEntity provider,
                                                         String configuredDownloadKey) {
        LlmProviderRepository repository = mock(LlmProviderRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.of(provider));
        LlmProperties properties = new LlmProperties();
        properties.setVideoDownloadApiKey(configuredDownloadKey);
        return new ProviderCredentialResolver(repository, properties);
    }

    @Test
    void publishesNoDownloadOriginByDefault() {
        ProviderCredentials credentials = resolverFor(provider(null)).resolve(7L);

        assertThat(credentials).isNotNull();
        assertThat(credentials.hasVideoResultBaseUrl()).isFalse();
        assertThat(credentials.hasVideoDownloadApiKey()).isFalse();
    }

    @Test
    void pairsADeclaredDownloadOriginWithTheSameRowKey() {
        ProviderCredentials credentials = resolverFor(provider("https://api.example.com")).resolve(7L);

        assertThat(credentials.videoResultBaseUrl()).isEqualTo("https://api.example.com");
        // Rotating the row key rotates the download key with it; the two cannot drift apart.
        assertThat(credentials.videoDownloadApiKey()).isEqualTo("sk-supplier");
        assertThat(credentials.baseUrl()).isEqualTo("http://127.0.0.1:3000/v1");
    }

    /**
     * The catalogue sync owns the relay supplier row and clears its {@code api_key} on every
     * refresh, so a key written there is gone within one sync interval. The origin survives, which
     * is why the download key has to be resolvable from somewhere the sync does not touch.
     */
    @Test
    void fallsBackToTheConfiguredKeyWhenTheRelayOwnedRowCarriesNone() {
        LlmProviderEntity relayRow = provider("https://api.example.com");
        relayRow.setApiKey(null);
        relayRow.setCredentialRef("config:new-api-user-relay");

        ProviderCredentials credentials =
                resolverFor(relayRow, "sk-demo-relay-video").resolve(7L);

        assertThat(credentials.videoResultBaseUrl()).isEqualTo("https://api.example.com");
        // Without this the supplier origin would be fetched with the relay key, which it rejects.
        assertThat(credentials.videoDownloadApiKey()).isEqualTo("sk-demo-relay-video");
        // The row still publishes no upstream key, so submit/poll keep using the caller's key.
        assertThat(credentials.apiKey()).isNull();
    }

    /**
     * The same configurable key now has a column of its own, so the origin and the credential that
     * belongs to it are both database rows: switching suppliers no longer needs a redeploy.
     */
    @Test
    void prefersTheDownloadKeyStoredOnTheRow() {
        LlmProviderEntity relayRow = provider("https://api.example.com");
        relayRow.setApiKey(null);
        relayRow.setCredentialRef("config:new-api-user-relay");
        relayRow.setVideoDownloadApiKey("sk-from-the-row");

        ProviderCredentials credentials =
                resolverFor(relayRow, "sk-from-the-environment").resolve(7L);

        assertThat(credentials.videoDownloadApiKey()).isEqualTo("sk-from-the-row");
        // Submit/poll are unaffected: the download column is not an upstream credential.
        assertThat(credentials.apiKey()).isNull();
    }

    /**
     * A row key is still the middle fallback: it is what a self-hosted supplier has, and it used to
     * be the first choice, so a deployment relying on it must not start needing a new column.
     */
    @Test
    void usesTheSubmitKeyWhenOnlyThatIsSet() {
        LlmProviderEntity self = provider("https://video.example");
        self.setVideoDownloadApiKey(null);

        ProviderCredentials credentials = resolverFor(self, "sk-from-the-environment").resolve(7L);

        assertThat(credentials.videoDownloadApiKey()).isEqualTo("sk-supplier");
    }

    @Test
    void keepsTheRelayKeyWhenNeitherTheRowNorConfigurationHasADownloadKey() {
        LlmProviderEntity relayRow = provider("https://api.example.com");
        relayRow.setApiKey(null);

        ProviderCredentials credentials = resolverFor(relayRow, "").resolve(7L);

        assertThat(credentials.hasVideoResultBaseUrl()).isTrue();
        assertThat(credentials.hasVideoDownloadApiKey()).isFalse();
    }

    @Test
    void trimsBlankDownloadOriginIntoSqlNull() {
        LlmProviderEntity provider = provider("   ");

        assertThat(provider.getVideoDownloadBaseUrl()).isNull();
    }

    @Test
    void trimsBlankDownloadKeyIntoSqlNull() {
        LlmProviderEntity provider = provider("https://api.example.com");
        provider.setVideoDownloadApiKey("  sk-test  ");
        assertThat(provider.getVideoDownloadApiKey()).isEqualTo("sk-test");

        // Blank must clear rather than store an empty string: the resolver tests hasVideoDownload-
        // ApiKey(), which is a blank check, so an empty value would read as "configured but empty".
        provider.setVideoDownloadApiKey("   ");
        assertThat(provider.getVideoDownloadApiKey()).isNull();
        assertThat(provider.hasVideoDownloadApiKey()).isFalse();
    }
}

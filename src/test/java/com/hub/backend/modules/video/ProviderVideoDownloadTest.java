package com.hub.backend.modules.video;

import com.hub.backend.infra.llm.ProviderCredentials;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderVideoDownloadTest {

    private static ProviderCredentials relay() {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE", "http://127.0.0.1:3000/v1", "sk-relay", null, null, null);
    }

    private static ProviderCredentials supplier(String downloadBase, String key) {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE", "https://api.example.com/v1", key, null, null, null,
                null, null, downloadBase, downloadBase == null ? null : key);
    }

    @Test
    void keepsTheRelayCredentialsWhenTheSupplierDeclaresNoOrigin() {
        ProviderCredentials merged = ProviderVideoDownload.merge(relay(), supplier(null, "sk-supplier"));

        assertThat(merged.baseUrl()).isEqualTo("http://127.0.0.1:3000/v1");
        assertThat(merged.apiKey()).isEqualTo("sk-relay");
        assertThat(merged.hasVideoResultBaseUrl()).isFalse();
        assertThat(merged.hasVideoDownloadApiKey()).isFalse();
    }

    @Test
    void keepsTheRelayCredentialsWhenThereIsNoSupplier() {
        ProviderCredentials merged = ProviderVideoDownload.merge(relay(), null);

        assertThat(merged.apiKey()).isEqualTo("sk-relay");
        assertThat(merged.hasVideoResultBaseUrl()).isFalse();
    }

    @Test
    void carriesTheSupplierOriginAndKeyButKeepsTheRelayForSubmitAndPoll() {
        ProviderCredentials merged = ProviderVideoDownload.merge(
                relay(), supplier("https://api.example.com", "sk-supplier"));

        // Submit and poll still authenticate as the caller; only the download is redirected.
        assertThat(merged.baseUrl()).isEqualTo("http://127.0.0.1:3000/v1");
        assertThat(merged.apiKey()).isEqualTo("sk-relay");
        assertThat(merged.videoResultBaseUrl()).isEqualTo("https://api.example.com");
        assertThat(merged.videoDownloadApiKey()).isEqualTo("sk-supplier");
    }

    @Test
    void usesTheSupplierKeyOnlyForURLsOnTheDeclaredOrigin() {
        ProviderCredentials merged = ProviderVideoDownload.merge(
                relay(), supplier("https://api.example.com", "sk-supplier"));

        assertThat(ProviderVideoDownload.usesProviderHost(
                merged, "https://api.example.com/v1/videos/abc/content")).isTrue();
        // The relay's own host must keep the relay key: sending the supplier key there would leak it.
        assertThat(ProviderVideoDownload.usesProviderHost(
                merged, "http://127.0.0.1:3000/v1/videos/task_x/content")).isFalse();
        assertThat(ProviderVideoDownload.usesProviderHost(
                merged, "https://vidgen.x.ai/v1/videos/abc/content")).isFalse();
        // A legal but different path is still the same origin.
        assertThat(ProviderVideoDownload.usesProviderHost(
                merged, "https://api.example.com:443/anything")).isTrue();
    }

    @Test
    void doesNotUseTheSupplierKeyForAHostLessPath() {
        ProviderCredentials merged = ProviderVideoDownload.merge(
                relay(), supplier("https://api.example.com", "sk-supplier"));

        assertThat(ProviderVideoDownload.usesProviderHost(merged, "/v1/videos/abc/content")).isFalse();
        assertThat(ProviderVideoDownload.usesProviderHost(merged, "")).isFalse();
        assertThat(ProviderVideoDownload.usesProviderHost(null, "https://api.example.com/x")).isFalse();
    }

    @Test
    void comparesSchemesAndExplicitPorts() {
        assertThat(ProviderVideoDownload.sameOrigin("https://a.example/x", "https://a.example")).isTrue();
        assertThat(ProviderVideoDownload.sameOrigin("http://a.example/x", "https://a.example")).isFalse();
        assertThat(ProviderVideoDownload.sameOrigin("https://a.example:8443/x", "https://a.example")).isFalse();
        assertThat(ProviderVideoDownload.sameOrigin("not a url", "https://a.example")).isFalse();
    }

    @Test
    void buildsRelayContentUrlFromBaseVideosPathAndTaskId() {
        assertThat(ProviderVideoDownload.relayContentUrl(
                "http://127.0.0.1:3000/v1", "/videos", "task_abc"))
                .isEqualTo("http://127.0.0.1:3000/v1/videos/task_abc/content");
        assertThat(ProviderVideoDownload.relayContentUrl(
                "http://127.0.0.1:3000/v1/", "videos/", "task_abc"))
                .isEqualTo("http://127.0.0.1:3000/v1/videos/task_abc/content");
        assertThat(ProviderVideoDownload.relayContentUrl(
                "http://127.0.0.1:3000/v1", null, "task_abc"))
                .isEqualTo("http://127.0.0.1:3000/v1/videos/task_abc/content");
        assertThat(ProviderVideoDownload.relayContentUrl(
                "http://127.0.0.1:3000/v1", "/videos", "  ")).isNull();
        assertThat(ProviderVideoDownload.relayContentUrl(null, "/videos", "task_abc")).isNull();
    }
}

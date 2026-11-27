package com.hub.backend.modules.admin.provider;

import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import com.hub.backend.modules.model.NewApiCatalogSyncService;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=admin-test-key",
        "app.new-api.catalog-sync-on-startup=false"
})
@AutoConfigureMockMvc
@Transactional
class LlmProviderAdminControllerTest {

    private static final String URL = "/api/admin/v1/providers";
    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LlmProviderRepository providerRepository;

    @MockitoBean
    private NewApiCatalogSyncService catalogSync;

    @Test
    void listsOnlyTheReadOnlyNewApiCatalogueWithoutCredentials() throws Exception {
        LlmProviderEntity provider = provider(
                "newapi-provider-read",
                "Read Provider",
                LlmProviderEntity.CATEGORY_CHAT,
                LlmProviderEntity.STATUS_ACTIVE);
        provider.setApiKey("sk-must-not-leak");
        provider.setCredentialRef("env:MUST_NOT_LEAK");
        provider.setCredentialFingerprint("fingerprint-must-not-leak");
        providerRepository.save(provider);

        LlmProviderEntity local = new LlmProviderEntity(
                "legacy-provider-read",
                "Legacy Provider",
                "OPENAI_COMPATIBLE",
                "https://legacy.example/v1",
                null,
                null,
                "BEARER",
                "env:LEGACY",
                LlmProviderEntity.STATUS_ACTIVE,
                0);
        providerRepository.save(local);

        mockMvc.perform(get(URL).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].providerKey", hasItem("newapi-provider-read")))
                .andExpect(jsonPath("$[*].providerKey", not(hasItem("legacy-provider-read"))))
                .andExpect(jsonPath(
                        "$[?(@.providerKey=='newapi-provider-read')].source",
                        hasItem("NEW_API")))
                .andExpect(jsonPath(
                        "$[?(@.providerKey=='newapi-provider-read')].providerGroup",
                        hasItem("default")))
                .andExpect(jsonPath(
                        "$[?(@.providerKey=='newapi-provider-read')].priority",
                        hasItem(50)))
                .andExpect(jsonPath(
                        "$[?(@.providerKey=='newapi-provider-read')].weight",
                        hasItem(7)))
                .andExpect(jsonPath("$[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$[0].credentialRef").doesNotExist())
                .andExpect(jsonPath("$[0].credentialFingerprint").doesNotExist());
    }

    @Test
    void filtersByCategoryProtocolAndStatus() throws Exception {
        provider(
                "newapi-provider-image-filter",
                "Image Filter",
                LlmProviderEntity.CATEGORY_IMAGE,
                LlmProviderEntity.STATUS_ACTIVE);
        provider(
                "newapi-provider-chat-filter",
                "Chat Filter",
                LlmProviderEntity.CATEGORY_CHAT,
                LlmProviderEntity.STATUS_DISABLED);

        mockMvc.perform(get(URL)
                        .param("category", "image")
                        .param("protocol", "openai_compatible")
                        .param("status", "ACTIVE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[*].providerKey",
                        hasItem("newapi-provider-image-filter")))
                .andExpect(jsonPath(
                        "$[*].providerKey",
                        not(hasItem("newapi-provider-chat-filter"))));
    }

    @Test
    void returnsDetailsForNewApiRowsAndHidesLegacyRows() throws Exception {
        LlmProviderEntity newApi = provider(
                "newapi-provider-detail",
                "New API Detail",
                LlmProviderEntity.CATEGORY_VIDEO,
                LlmProviderEntity.STATUS_ACTIVE);
        LlmProviderEntity local = providerRepository.save(new LlmProviderEntity(
                "legacy-provider-detail",
                "Legacy Detail",
                "OPENAI_COMPATIBLE",
                "https://legacy.example/v1",
                null,
                null,
                "BEARER",
                "env:LEGACY",
                LlmProviderEntity.STATUS_ACTIVE,
                0));

        mockMvc.perform(get(URL + "/" + newApi.getId()).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerKey").value("newapi-provider-detail"))
                .andExpect(jsonPath("$.externalId").value("newapi-provider-detail"))
                .andExpect(jsonPath("$.category").value("VIDEO"))
                .andExpect(jsonPath("$.upstreamAvailable").value(true));

        mockMvc.perform(get(URL + "/" + local.getId()).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void removesManualProviderCrudAndStatusRoutes() throws Exception {
        LlmProviderEntity provider = provider(
                "newapi-provider-no-crud",
                "No CRUD",
                LlmProviderEntity.CATEGORY_CHAT,
                LlmProviderEntity.STATUS_ACTIVE);

        mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerKey\":\"manual\"}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL + "/" + provider.getId())
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Manual update\"}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL + "/" + provider.getId() + "/status")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(URL + "/" + provider.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void protectsCatalogSyncAndRunsWithoutManagementToken() throws Exception {
        when(catalogSync.sync()).thenReturn(new NewApiCatalogSyncResponse(
                "NEW_API", Instant.parse("2026-10-31T00:00:00Z"),
                1, 1, 0, 0,
                2, 2, 0, 0,
                0, 0, 0, 0,
                2, 0, 0, 0, 0));

        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/sync"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/sync").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("NEW_API"))
                .andExpect(jsonPath("$.providersTotal").value(1))
                .andExpect(jsonPath("$.modelsTotal").value(2));
    }

    @Test
    void setsAndClearsTheVideoDownloadOriginOneFieldAtATime() throws Exception {
        LlmProviderEntity provider = provider(
                "newapi-provider-download",
                "Download Origin",
                LlmProviderEntity.CATEGORY_VIDEO,
                LlmProviderEntity.STATUS_ACTIVE);
        String route = URL + "/" + provider.getId() + "/video-download-base-url";

        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"https://api.example.com/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoDownloadBaseUrl").value("https://api.example.com"))
                // The field is a host, never a key: the response must stay credential-free.
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.credentialRef").doesNotExist());

        // Blank clears the override, which restores "resolve against the relay".
        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoDownloadBaseUrl").doesNotExist());

        // A relative value would resolve against the relay again, so it is rejected up front.
        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"/v1/videos\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"https://api.example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put(URL + "/999999/video-download-base-url")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"https://api.example.com\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(route)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"https://api.example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The download credential is the one thing that used to require an environment variable, so
     * the write path and the "never echo the secret" rule both matter here.
     */
    @Test
    void setsAndClearsTheVideoDownloadKeyWithoutEchoingIt() throws Exception {
        LlmProviderEntity provider = provider(
                "newapi-provider-download-key",
                "Download Key",
                LlmProviderEntity.CATEGORY_VIDEO,
                LlmProviderEntity.STATUS_ACTIVE);
        String route = URL + "/" + provider.getId() + "/video-download-api-key";

        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"sk-demo-relay-video-0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoDownloadApiKeySet").value(true))
                // The secret must never come back, not even on the write that set it.
                .andExpect(jsonPath("$.videoDownloadApiKey").doesNotExist())
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        LlmProviderEntity stored =
                providerRepository.findById(provider.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(
                "sk-demo-relay-video-0001", stored.getVideoDownloadApiKey());

        // A key that already carries the scheme would be sent as "Bearer Bearer sk-...".
        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Bearer sk-demo-relay-video-0001\"}"))
                .andExpect(status().isBadRequest());
        // A copy-pasted key usually brings a trailing newline or a space.
        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"sk-ji guang\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(route)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoDownloadApiKeySet").value(false));

        mockMvc.perform(put(URL + "/999999/video-download-api-key")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"sk-x\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(route)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"sk-x\"}"))
                .andExpect(status().isUnauthorized());
    }

    private LlmProviderEntity provider(String key,
                                       String name,
                                       String category,
                                       String status) {
        LlmProviderEntity provider = new LlmProviderEntity(
                key,
                name,
                "OPENAI_COMPATIBLE",
                "https://gateway.example/v1",
                null,
                null,
                "BEARER",
                "config:new-api-user-relay",
                status,
                0);
        provider.setSource(LlmProviderEntity.SOURCE_NEW_API);
        provider.setExternalId(key);
        provider.setCategory(category);
        provider.setUpstreamAvailable(LlmProviderEntity.STATUS_ACTIVE.equals(status));
        provider.setProviderGroup("default");
        provider.setPriority(50);
        provider.setWeight(7);
        return providerRepository.save(provider);
    }
}

package com.hub.backend.modules.model;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiModelPlaza;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import com.hub.backend.modules.model.entity.NewApiGroupEntity;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.NewApiGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.new-api.group=default",
        "app.new-api.base-url=https://new-api.example",
        "app.new-api.catalog-sync-on-startup=false"
})
@Transactional
class NewApiCatalogSyncServiceTest {

    private static final String PUBLIC_PROVIDER_KEY = "newapi-public-model-plaza";
    private static final String PUBLIC_GROUP_KEY = "newapi-public-model-plaza";

    @Autowired
    private NewApiCatalogSyncService syncService;

    @Autowired
    private NewApiProperties newApiProperties;

    @Autowired
    private LlmProviderRepository providerRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private LlmModelGroupRepository groupRepository;

    @Autowired
    private NewApiGroupRepository relayGroupRepository;

    @Autowired
    private LlmModelFallbackGroupRepository fallbackGroupRepository;

    @Autowired
    private LlmModelFallbackMemberRepository fallbackMemberRepository;

    @Autowired
    private ModelFallbackCatalogService fallbackCatalog;

    @MockitoBean
    private NewApiManagementClient newApi;

    @Test
    void syncsAnonymousPlazaIntoOnePublicProviderAndGroup() {
        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai",
                        model("gpt-5", pricing("0", "0.000002")),
                        model("gpt-image-2", null)),
                group(2L, "media", "xai",
                        model("sora", null),
                        model("whisper", null))));

        var result = syncService.sync();

        LlmProviderEntity provider = publicProvider();
        assertEquals(LlmProviderEntity.SOURCE_NEW_API, provider.getSource());
        assertEquals(PUBLIC_PROVIDER_KEY, provider.getExternalId());
        assertEquals("https://new-api.example", provider.getBaseUrl());
        assertEquals("config:new-api-user-relay", provider.getCredentialRef());
        assertNull(provider.getApiKey());
        assertNull(provider.getCredentialFingerprint());
        assertTrue(provider.isUpstreamAvailable());
        assertEquals(LlmProviderEntity.STATUS_ACTIVE, provider.getStatus());

        LlmModelGroupEntity group = publicGroup();
        assertEquals("New API Public Model Plaza", group.getLabel());
        assertEquals(LlmModelEntity.STATUS_ACTIVE, group.getStatus());

        LlmModelEntity chat = newApiModel("gpt-5");
        assertEquals(provider.getId(), chat.getProviderId());
        assertEquals(group.getId(), chat.getGroupId());
        assertNull(chat.getSiteId());
        assertEquals("gpt-5", chat.getRoute());
        assertEquals(LlmModelEntity.TYPE_CHAT, chat.getModelType());
        assertTrue(chat.isUpstreamAvailable());

        assertEquals(LlmModelEntity.TYPE_IMAGE,
                newApiModel("gpt-image-2").getModelType());
        assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("sora").getModelType());
        assertEquals(LlmModelEntity.TYPE_AUDIO, newApiModel("whisper").getModelType());

        // 目录同步只维护模型库：不上架任何版本组，版本组一律由运营手工编排。
        assertTrue(fallbackGroupRepository.findAll().isEmpty());
        assertTrue(fallbackMemberRepository.findAll().isEmpty());

        assertEquals(1, result.providersTotal());
        assertEquals(1, result.providersCreated());
        assertEquals(0, result.providersUpdated());
        assertEquals(4, result.modelsTotal());
        assertEquals(4, result.modelsCreated());
        assertEquals(3, result.pricingUnpriced());

        verify(newApi).fetchModelPlaza();
        verifyNoMoreInteractions(newApi);
    }

    @Test
    void classifiesCommonVideoVendorNamesAsVideo() {
        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "media", "bytedance",
                        model("seedance-2.0", null),
                        model("wan-2.5", null),
                        model("hailuo-02", null),
                        model("runway-gen3", null))));

        syncService.sync();

        assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("seedance-2.0").getModelType());
        assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("wan-2.5").getModelType());
        assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("hailuo-02").getModelType());
        assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("runway-gen3").getModelType());
    }

    @Test
    void seedsDefaultMaxReferenceImagesForVideoModelsWithoutOverwritingOperatorCaps() {
        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "media", "xai",
                        model("grok-imagine-video-1.5", null),
                        model("grok-imagine-video", null),
                        model("sora", null))));

        syncService.sync();

        assertTrue(newApiModel("grok-imagine-video-1.5").getCapabilitiesJson()
                .contains("\"maxReferenceImages\":14"));
        assertTrue(newApiModel("grok-imagine-video").getCapabilitiesJson()
                .contains("\"maxReferenceImages\":7"));
        assertTrue(newApiModel("sora").getCapabilitiesJson()
                .contains("\"maxReferenceImages\":4"));

        LlmModelEntity edited = newApiModel("sora");
        edited.setCapabilitiesJson(
                "{\"modes\":[\"text2video\"],\"maxReferenceImages\":2,\"audio\":false}");
        modelRepository.saveAndFlush(edited);

        syncService.sync();

        assertEquals(
                "{\"modes\":[\"text2video\"],\"maxReferenceImages\":2,\"audio\":false}",
                newApiModel("sora").getCapabilitiesJson(),
                "operator-edited video capabilities must survive a refresh");
    }

    @Test
    void appliesConfiguredModelTypeOverridesAndPreservesOperatorTypeOnRefresh() {
        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "media", "vendor",
                        model("odd-vendor-clip", null),
                        model("mislabelled-chat", null))));

        newApiProperties.setModelTypeOverrides(Map.of("odd-vendor-clip", "VIDEO"));
        try {
            syncService.sync();
            assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("odd-vendor-clip").getModelType());
            assertEquals(LlmModelEntity.TYPE_CHAT, newApiModel("mislabelled-chat").getModelType());

            LlmModelEntity fixed = newApiModel("mislabelled-chat");
            fixed.setModelType(LlmModelEntity.TYPE_VIDEO);
            modelRepository.saveAndFlush(fixed);

            syncService.sync();

            assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("odd-vendor-clip").getModelType());
            assertEquals(LlmModelEntity.TYPE_VIDEO, newApiModel("mislabelled-chat").getModelType(),
                    "operator-corrected type must survive a refresh without an override");
        } finally {
            newApiProperties.setModelTypeOverrides(Map.of());
        }
    }

    @Test
    void preservesOperatorOwnedFieldsAcrossAnonymousRefreshes() {
        LlmModelEntity existing = new LlmModelEntity(
                "gpt-5", "Operator Name", "Operator Company", "legacy-route");
        existing.setSource(LlmModelEntity.SOURCE_NEW_API);
        existing.setExternalId("gpt-5");
        existing.setStatus("DISABLED");
        existing.setDescription("Operator description");
        existing.setIconUrl("https://cdn.example/gpt.png");
        existing.setCapabilitiesJson("{\"vision\":true}");
        existing.setContextWindow(200_000);
        existing.setMaxOutputTokens(50_000);
        existing.setSortOrder(42);
        existing.setUpstreamAvailable(false);
        existing = modelRepository.saveAndFlush(existing);
        Long originalId = existing.getId();

        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai", model("gpt-5", pricing("0.000001", null)))));

        syncService.sync();
        syncService.sync();

        LlmModelEntity refreshed = newApiModel("gpt-5");
        assertEquals(originalId, refreshed.getId());
        assertEquals("Operator Name", refreshed.getModelName());
        assertEquals("Operator Company", refreshed.getCompany());
        assertEquals("DISABLED", refreshed.getStatus());
        assertEquals("Operator description", refreshed.getDescription());
        assertEquals("https://cdn.example/gpt.png", refreshed.getIconUrl());
        assertEquals("{\"vision\":true}", refreshed.getCapabilitiesJson());
        assertEquals(200_000, refreshed.getContextWindow());
        assertEquals(50_000, refreshed.getMaxOutputTokens());
        assertEquals(42, refreshed.getSortOrder());
        assertEquals("gpt-5", refreshed.getRoute());
        assertEquals(publicProvider().getId(), refreshed.getProviderId());
        assertEquals(publicGroup().getId(), refreshed.getGroupId());
        assertTrue(refreshed.isUpstreamAvailable());
    }

    @Test
    void marksMissingModelsUnavailableWithoutDeletingTheirLocalRows() {
        when(newApi.fetchModelPlaza())
                .thenReturn(plaza(group(1L, "default", "openai",
                        model("kept-model", pricing("0.000001", null)),
                        model("removed-model", pricing("0.000002", null)))))
                .thenReturn(plaza(group(1L, "default", "openai",
                        model("kept-model", pricing("0.000001", null)))));

        syncService.sync();
        Long removedId = newApiModel("removed-model").getId();

        var result = syncService.sync();

        LlmModelEntity removed = modelRepository.findById(removedId).orElseThrow();
        assertFalse(removed.isUpstreamAvailable());
        assertTrue(newApiModel("kept-model").isUpstreamAvailable());
        assertEquals(1, result.modelsUnavailable());
        assertEquals(1, result.modelsUpdated());
    }

    @Test
    void neverPublishesVersionGroupsForSyncedModels() {
        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai",
                        model("gpt-5", pricing("0.000001", null)),
                        model("gpt-image-2", null)),
                group(2L, "media", "xai",
                        model("sora", null))));

        syncService.sync();
        syncService.sync();

        assertEquals(3, modelRepository.findBySource(LlmModelEntity.SOURCE_NEW_API).size());
        assertTrue(fallbackGroupRepository.findAll().isEmpty());
        assertTrue(fallbackMemberRepository.findAll().isEmpty());
        assertTrue(fallbackCatalog.visibleGroups(null, null).isEmpty());
    }

    @Test
    void disablesLegacyChannelProvidersAndClearsTheirCredentials() {
        LlmProviderEntity legacy = new LlmProviderEntity(
                "newapi-channel-101",
                "Legacy New API Channel",
                "OPENAI_COMPATIBLE",
                "https://upstream.example/v1",
                null,
                null,
                "BEARER",
                "config:new-api-management",
                LlmProviderEntity.STATUS_ACTIVE,
                0);
        legacy.setSource(LlmProviderEntity.SOURCE_NEW_API);
        legacy.setExternalId("101");
        legacy.setApiKey("legacy-management-secret");
        legacy.setCredentialFingerprint("legacy-fingerprint");
        legacy = providerRepository.saveAndFlush(legacy);

        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai", model("gpt-5", pricing("0.000001", null)))));

        var result = syncService.sync();

        LlmProviderEntity disabled = providerRepository.findById(legacy.getId()).orElseThrow();
        assertEquals(LlmProviderEntity.STATUS_DISABLED, disabled.getStatus());
        assertFalse(disabled.isUpstreamAvailable());
        assertNull(disabled.getApiKey());
        assertNull(disabled.getCredentialFingerprint());
        assertTrue(providerRepository.findByProviderKey(PUBLIC_PROVIDER_KEY).isPresent());
        assertEquals(1, result.providersUnavailable());
        assertEquals(1, result.providersCreated());
    }

    @Test
    void emptyOrFailedAnonymousSnapshotsLeaveTheCurrentCatalogueUntouched() {
        NewApiModelPlaza good = plaza(
                group(1L, "default", "openai", model("gpt-5", pricing("0.000001", null))));
        when(newApi.fetchModelPlaza())
                .thenReturn(good)
                .thenReturn(new NewApiModelPlaza("No public models", List.of()))
                .thenThrow(new ServiceUnavailableException("plaza unavailable"));

        syncService.sync();
        long providersBefore = providerRepository.count();
        long modelsBefore = modelRepository.count();
        Long modelId = newApiModel("gpt-5").getId();

        assertThrows(ServiceUnavailableException.class, syncService::sync);
        assertSnapshotUnchanged(providersBefore, modelsBefore, modelId);

        assertThrows(ServiceUnavailableException.class, syncService::sync);
        assertSnapshotUnchanged(providersBefore, modelsBefore, modelId);
        verify(newApi, times(3)).fetchModelPlaza();
    }

    @Test
    void deduplicatesTheSameModelAcrossPublicGroups() {
        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai", model("shared-model", pricing("0.000002", null))),
                group(2L, "discount", "azure", model("shared-model", pricing("0.000001", null)))));

        var result = syncService.sync();

        List<LlmModelEntity> matches = modelRepository.findBySource(LlmModelEntity.SOURCE_NEW_API)
                .stream()
                .filter(model -> "shared-model".equals(model.getExternalId()))
                .toList();
        assertEquals(1, matches.size());
        assertEquals(1, result.modelsTotal());
        assertEquals(1, result.modelsCreated());
        assertEquals(0, result.pricingUnpriced());
    }

    @Test
    void leavesOperatorCuratedGroupsUntouched() {
        LlmModelEntity curated = new LlmModelEntity(
                "gpt-5", "GPT-5", "OpenAI", "gpt-5");
        curated.setSource(LlmModelEntity.SOURCE_NEW_API);
        curated.setExternalId("gpt-5");
        curated.setStatus(LlmModelEntity.STATUS_ACTIVE);
        curated.setUpstreamAvailable(true);
        curated = modelRepository.saveAndFlush(curated);

        LlmModelFallbackGroupEntity operatorGroup = fallbackGroupRepository.saveAndFlush(
                new LlmModelFallbackGroupEntity(
                        null, "gpt-5-version", "GPT-5 版本组", LlmModelEntity.TYPE_CHAT));
        fallbackMemberRepository.saveAndFlush(new LlmModelFallbackMemberEntity(
                operatorGroup.getId(), curated.getId(), 0));

        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai", model("gpt-5", pricing("0.000001", null)))));

        syncService.sync();

        assertEquals(1, fallbackGroupRepository.findAll().size());
        LlmModelFallbackGroupEntity kept = fallbackGroupRepository
                .findBySiteIdIsNullAndGroupKey("gpt-5-version")
                .orElseThrow();
        assertEquals(operatorGroup.getId(), kept.getId());
        assertEquals(operatorGroup.getDisplayName(), kept.getDisplayName());
    }

    @Test
    void preservesTheVideoDownloadOriginAndKeyAcrossRefreshes() {
        // The whole point of the dedicated column: api_key is cleared by design on every refresh,
        // so a download credential stored there would silently fall back to the relay key within
        // one sync interval — which is why it used to live in an environment variable.
        LlmProviderEntity seeded = new LlmProviderEntity(
                PUBLIC_PROVIDER_KEY,
                "New API Public Model Plaza",
                "OPENAI_COMPATIBLE",
                "https://new-api.example",
                null,
                null,
                "BEARER",
                "config:new-api-user-relay",
                LlmProviderEntity.STATUS_ACTIVE,
                0);
        seeded.setSource(LlmProviderEntity.SOURCE_NEW_API);
        seeded.setExternalId(PUBLIC_PROVIDER_KEY);
        seeded.setApiKey("sk-must-be-cleared");
        seeded.setVideoDownloadBaseUrl("https://api.example.com");
        seeded.setVideoDownloadApiKey("sk-demo-relay-video");
        providerRepository.saveAndFlush(seeded);

        when(newApi.fetchModelPlaza()).thenReturn(plaza(
                group(1L, "default", "openai", model("gpt-5", pricing("0.000001", null)))));

        syncService.sync();
        syncService.sync();

        LlmProviderEntity refreshed = publicProvider();
        assertNull(refreshed.getApiKey(), "the synced upstream key is operator-free by design");
        assertEquals("https://api.example.com", refreshed.getVideoDownloadBaseUrl());
        assertEquals("sk-demo-relay-video", refreshed.getVideoDownloadApiKey(),
                "the download key must survive the sync that clears api_key");
    }

    @Test
    void recordsTheGroupsAModelIsServedInAndTheSelectableGroups() {
        when(newApi.fetchModelPlaza()).thenReturn(catalogueWithGroups(
                List.of(new NewApiModelPlaza.GroupInfo("default", "Default group", BigDecimal.ONE),
                        new NewApiModelPlaza.GroupInfo("vip", "VIP group", new BigDecimal("2")))));

        syncService.sync();

        // enable_groups arrives untrimmed even though the caller is in `default`, so one read is
        // enough to know every group a model can be relayed through.
        assertEquals("default,vip", newApiModel("gpt-5").getUpstreamGroups());
        assertEquals("default", newApiModel("gpt-image-2").getUpstreamGroups());

        assertEquals(2, relayGroupRepository.count());
        NewApiGroupEntity vip = relayGroupRepository.findByGroupKey("vip").orElseThrow();
        assertEquals("VIP group", vip.getLabel());
        assertEquals(0, new BigDecimal("2").compareTo(vip.getRatio()));
        assertTrue(vip.isUsable());
    }

    @Test
    void flagsAGroupThatLeavesThePlazaWithoutDeletingIt() {
        when(newApi.fetchModelPlaza())
                .thenReturn(catalogueWithGroups(
                        List.of(new NewApiModelPlaza.GroupInfo("default", "Default group", BigDecimal.ONE),
                                new NewApiModelPlaza.GroupInfo("vip", "VIP group", BigDecimal.ONE))))
                .thenReturn(new NewApiModelPlaza(
                        "Public catalogue",
                        List.of(group(1L, "default", "openai", model("gpt-5", pricing("0", "0.000002")))),
                        List.of(new NewApiModelPlaza.GroupInfo(
                                "default", "Default group", BigDecimal.ONE))));

        syncService.sync();
        syncService.sync();

        // The row stays: an existing token may still carry the name, and its label/ratio are what
        // explains that token on the console.
        NewApiGroupEntity vip = relayGroupRepository.findByGroupKey("vip").orElseThrow();
        assertFalse(vip.isUsable());
        assertTrue(relayGroupRepository.findByGroupKey("default").orElseThrow().isUsable());
    }

    private NewApiModelPlaza catalogueWithGroups(List<NewApiModelPlaza.GroupInfo> availableGroups) {
        return new NewApiModelPlaza(
                "Public catalogue",
                List.of(group(1L, "default", "openai",
                        new NewApiModelPlaza.Model(
                                "gpt-5", "openai", pricing("0", "0.000002"),
                                List.of("default", "vip")),
                        new NewApiModelPlaza.Model(
                                "gpt-image-2", "openai", null, List.of("default")))),
                availableGroups);
    }

    private void assertSnapshotUnchanged(long providersBefore,
                                         long modelsBefore,
                                         Long modelId) {
        assertEquals(providersBefore, providerRepository.count());
        assertEquals(modelsBefore, modelRepository.count());
        LlmModelEntity model = modelRepository.findById(modelId).orElseThrow();
        assertTrue(model.isUpstreamAvailable());
        assertEquals(PUBLIC_PROVIDER_KEY, publicProvider().getProviderKey());
    }

    private LlmModelEntity newApiModel(String externalId) {
        return modelRepository.findBySourceAndExternalId(
                        LlmModelEntity.SOURCE_NEW_API, externalId)
                .orElseThrow();
    }

    private LlmProviderEntity publicProvider() {
        return providerRepository.findByProviderKey(PUBLIC_PROVIDER_KEY).orElseThrow();
    }

    private LlmModelGroupEntity publicGroup() {
        return groupRepository
                .findFirstBySiteIdIsNullAndGroupKeyOrderByIdAsc(PUBLIC_GROUP_KEY)
                .orElseThrow();
    }

    private static NewApiModelPlaza plaza(NewApiModelPlaza.Group... groups) {
        return new NewApiModelPlaza("Public catalogue", List.of(groups));
    }

    private static NewApiModelPlaza.Group group(
            Long id,
            String name,
            String platform,
            NewApiModelPlaza.Model... models) {
        return new NewApiModelPlaza.Group(id, name, platform, List.of(models));
    }

    private static NewApiModelPlaza.Model model(
            String name,
            NewApiModelPlaza.Pricing pricing) {
        return new NewApiModelPlaza.Model(name, "openai", pricing);
    }

    private static NewApiModelPlaza.Pricing pricing(String input, String output) {
        return new NewApiModelPlaza.Pricing(
                "token",
                decimal(input),
                decimal(output),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}

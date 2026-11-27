package com.hub.backend.modules.model;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiModelPlaza;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import com.hub.backend.modules.model.entity.NewApiGroupEntity;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.NewApiGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Mirrors the New API catalogue into the local read model.
 *
 * <p>Only {@code GET /api/pricing} is used. No management token is read, no channel is discovered
 * or written back, and no New API price is changed. When the plaza requires a login the request is
 * authenticated with the optional sync-only account ({@code app.new-api.catalog-username}); that
 * account's group decides which models come back. A failed or empty snapshot leaves the previous
 * local catalogue untouched.</p>
 *
 * <p>The provider/group keys still read {@code newapi-public-model-plaza} on purpose: they are
 * persisted identifiers, and renaming them would orphan the existing rows.</p>
 */
@Service
public class NewApiCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(NewApiCatalogSyncService.class);

    private static final String SOURCE = LlmModelEntity.SOURCE_NEW_API;
    private static final String PROVIDER_KEY = "newapi-public-model-plaza";
    private static final String GROUP_KEY = "newapi-public-model-plaza";
    private static final String PROVIDER_PROTOCOL = "OPENAI_COMPATIBLE";
    private static final String PROVIDER_AUTH_TYPE = "BEARER";
    private static final String PROVIDER_CREDENTIAL_REF = "config:new-api-user-relay";

    private final NewApiManagementClient newApi;
    private final NewApiProperties properties;
    private final LlmProviderRepository providers;
    private final LlmModelRepository models;
    private final LlmModelGroupRepository groups;
    private final NewApiGroupRepository relayGroups;
    private final TransactionTemplate transactions;

    public NewApiCatalogSyncService(NewApiManagementClient newApi,
                                    NewApiProperties properties,
                                    LlmProviderRepository providers,
                                    LlmModelRepository models,
                                    LlmModelGroupRepository groups,
                                    NewApiGroupRepository relayGroups,
                                    PlatformTransactionManager transactionManager) {
        this.newApi = newApi;
        this.properties = properties;
        this.providers = providers;
        this.models = models;
        this.groups = groups;
        this.relayGroups = relayGroups;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Refreshes the public model cache. The anonymous network call completes before the database
     * transaction starts, so an unavailable plaza never changes local state.
     */
    public synchronized NewApiCatalogSyncResponse sync() {
        NewApiModelPlaza plaza = newApi.fetchModelPlaza();
        Map<String, PublicModel> publicModels = publicModels(plaza);
        if (publicModels.isEmpty()) {
            throw new ServiceUnavailableException(
                    "New API model plaza did not publish any models");
        }

        Instant syncedAt = Instant.now();
        List<NewApiModelPlaza.GroupInfo> availableGroups = plaza.availableGroups();
        NewApiCatalogSyncResponse response = transactions.execute(
                status -> applySnapshot(publicModels, availableGroups, syncedAt));
        if (response == null) {
            throw new ServiceUnavailableException(
                    "New API public catalogue sync did not complete");
        }
        return response;
    }

    private NewApiCatalogSyncResponse applySnapshot(Map<String, PublicModel> publicModels,
                                                    List<NewApiModelPlaza.GroupInfo> availableGroups,
                                                    Instant syncedAt) {
        LlmModelGroupEntity group = ensurePublicGroup();
        int relayGroupsSynced = syncRelayGroups(availableGroups, syncedAt);
        List<LlmProviderEntity> existingProviders =
                providers.findBySourceOrderByPriorityDescWeightDescExternalIdAsc(SOURCE);
        boolean providerExisted = existingProviders.stream()
                .anyMatch(provider -> PROVIDER_KEY.equals(provider.getProviderKey()));
        LlmProviderEntity publicProvider = ensurePublicProvider();

        int providersUnavailable = 0;
        for (LlmProviderEntity provider : existingProviders) {
            if (PROVIDER_KEY.equals(provider.getProviderKey())) {
                continue;
            }
            provider.setStatus(LlmProviderEntity.STATUS_DISABLED);
            provider.setUpstreamAvailable(false);
            provider.setApiKey(null);
            provider.setCredentialFingerprint(null);
            providersUnavailable++;
        }
        providers.saveAll(existingProviders.stream()
                .filter(provider -> !PROVIDER_KEY.equals(provider.getProviderKey()))
                .toList());

        Set<String> modelTypes = new LinkedHashSet<>();
        List<LlmModelEntity> existingModels = models.findBySource(SOURCE);
        Map<String, LlmModelEntity> existingByExternalId = new LinkedHashMap<>();
        for (LlmModelEntity model : existingModels) {
            model.setUpstreamAvailable(false);
            if (model.getExternalId() != null && !model.getExternalId().isBlank()) {
                existingByExternalId.putIfAbsent(model.getExternalId(), model);
            }
            if (model.getModelKey() != null && !model.getModelKey().isBlank()) {
                existingByExternalId.putIfAbsent(model.getModelKey(), model);
            }
        }

        int modelsCreated = 0;
        int modelsUpdated = 0;
        int sortOrder = 0;
        for (Map.Entry<String, PublicModel> entry : publicModels.entrySet()) {
            String modelKey = entry.getKey();
            PublicModel discovered = entry.getValue();
            LlmModelEntity model = existingByExternalId.get(modelKey);
            boolean created = model == null;
            if (created) {
                model = new LlmModelEntity(
                        modelKey,
                        modelKey,
                        inferredCompany(modelKey),
                        modelKey);
                model.setSource(SOURCE);
                model.setExternalId(modelKey);
                model.setStatus(LlmModelEntity.STATUS_ACTIVE);
                model.setSortOrder(sortOrder);
                modelsCreated++;
            } else {
                modelsUpdated++;
            }

            String inferredType = modelType(modelKey, discovered);
            model.setSource(SOURCE);
            model.setExternalId(modelKey);
            model.setModelKey(modelKey);
            model.setRoute(modelKey);
            // New rows take the inferred type. Existing rows keep an operator-set type unless a
            // configured override is present — overrides always win so ops can force a fix via yml.
            if (created || properties.modelTypeOverride(modelKey) != null) {
                model.setModelType(inferredType);
            }
            model.setProviderId(publicProvider.getId());
            model.setGroupId(group.getId());
            model.setSiteId(null);
            model.setUpstreamAvailable(true);
            model.setUpstreamGroups(discovered.groupsCsv());
            if (model.getModelName() == null || model.getModelName().isBlank()) {
                model.setModelName(modelKey);
            }
            if (model.getCompany() == null || model.getCompany().isBlank()) {
                model.setCompany(inferredCompany(modelKey));
            }
            seedVideoCapabilitiesIfAbsent(model);
            models.save(model);
            modelTypes.add(model.getModelType());
            sortOrder++;
        }

        publicProvider.setCategory(categoryOf(modelTypes));
        providers.save(publicProvider);

        int missingModels = 0;
        for (LlmModelEntity model : existingModels) {
            String externalId = model.getExternalId();
            String modelKey = model.getModelKey();
            if ((externalId == null || !publicModels.containsKey(externalId))
                    && (modelKey == null || !publicModels.containsKey(modelKey))) {
                missingModels++;
            }
        }

        int unpriced = (int) publicModels.values().stream()
                .filter(model -> !hasExplicitPrice(model.pricing()))
                .count();
        int providerCreated = providerExisted ? 0 : 1;
        int providerUpdated = providerCreated == 0 ? 1 : 0;

        log.info(
                "New API model plaza synced: {} model(s), {} new, {} missing, {} group(s)",
                publicModels.size(), modelsCreated, missingModels, relayGroupsSynced);
        return new NewApiCatalogSyncResponse(
                SOURCE,
                syncedAt,
                1,
                providerCreated,
                providerUpdated,
                providersUnavailable,
                publicModels.size(),
                modelsCreated,
                modelsUpdated,
                missingModels,
                1,
                0,
                0,
                0,
                publicModels.size(),
                0,
                0,
                0,
                unpriced);
    }

    private LlmProviderEntity ensurePublicProvider() {
        LlmProviderEntity provider = providers.findByProviderKey(PROVIDER_KEY)
                .or(() -> providers.findBySourceAndExternalId(SOURCE, PROVIDER_KEY))
                .orElseGet(() -> new LlmProviderEntity(
                        PROVIDER_KEY,
                        "New API Public Model Plaza",
                        PROVIDER_PROTOCOL,
                        properties.resolvedBaseUrl(),
                        null,
                        null,
                        PROVIDER_AUTH_TYPE,
                        PROVIDER_CREDENTIAL_REF,
                        LlmProviderEntity.STATUS_ACTIVE,
                        0));
        provider.setSource(SOURCE);
        provider.setExternalId(PROVIDER_KEY);
        provider.setProviderKey(PROVIDER_KEY);
        provider.setName("New API Public Model Plaza");
        provider.setProtocol(PROVIDER_PROTOCOL);
        provider.setBaseUrl(properties.resolvedBaseUrl());
        provider.setEndpointPath(null);
        provider.setImagesPath(null);
        provider.setApiVersion(null);
        provider.setAuthType(PROVIDER_AUTH_TYPE);
        provider.setCredentialRef(PROVIDER_CREDENTIAL_REF);
        provider.setApiKey(null);
        provider.setCredentialFingerprint(null);
        provider.setMetadataJson(null);
        provider.setProviderGroup(null);
        provider.setPriority(0);
        provider.setWeight(0);
        provider.setSortOrder(0);
        provider.setStatus(LlmProviderEntity.STATUS_ACTIVE);
        provider.setUpstreamAvailable(true);
        return providers.save(provider);
    }

    private LlmModelGroupEntity ensurePublicGroup() {
        return groups.findFirstBySiteIdIsNullAndGroupKeyOrderByIdAsc(GROUP_KEY)
                .map(group -> {
                    group.setLabel("New API Public Model Plaza");
                    group.setCompany("New API");
                    group.setStatus(LlmModelEntity.STATUS_ACTIVE);
                    return groups.save(group);
                })
                .orElseGet(() -> groups.save(new LlmModelGroupEntity(
                        null,
                        GROUP_KEY,
                        "New API Public Model Plaza",
                        "New API")));
    }

    /**
     * Mirrors the plaza's selectable groups into {@code newapi_groups}.
     *
     * <p>A group that disappears from the snapshot is only flagged unusable: tokens already
     * carrying its name keep working until New API refuses them, and deleting the row would lose
     * the label and ratio needed to explain an existing token.</p>
     *
     * @return number of groups the snapshot published
     */
    private int syncRelayGroups(List<NewApiModelPlaza.GroupInfo> availableGroups,
                                Instant syncedAt) {
        if (availableGroups.isEmpty()) {
            return 0;
        }
        LocalDateTime at = LocalDateTime.ofInstant(syncedAt, ZoneId.systemDefault());
        Set<String> seen = new LinkedHashSet<>();
        for (NewApiModelPlaza.GroupInfo group : availableGroups) {
            if (group == null || group.name() == null || group.name().isBlank()) {
                continue;
            }
            String key = group.name().strip();
            seen.add(key);
            NewApiGroupEntity entity = relayGroups.findByGroupKey(key)
                    .orElseGet(() -> new NewApiGroupEntity(key, group.label(), group.ratio(), at));
            entity.refresh(group.label(), group.ratio(), true, at);
            relayGroups.save(entity);
        }
        for (NewApiGroupEntity entity : relayGroups.findAllByOrderByRatioAscGroupKeyAsc()) {
            if (entity.isUsable() && !seen.contains(entity.getGroupKey())) {
                entity.refresh(entity.getLabel(), entity.getRatio(), false, at);
                relayGroups.save(entity);
            }
        }
        return seen.size();
    }

    private static Map<String, PublicModel> publicModels(NewApiModelPlaza plaza) {
        if (plaza == null || plaza.groups().isEmpty()) {
            return Map.of();
        }
        Map<String, PublicModel> result = new LinkedHashMap<>();
        for (NewApiModelPlaza.Group group : plaza.groups()) {
            for (NewApiModelPlaza.Model model : group.models()) {
                if (model == null || model.name() == null || model.name().isBlank()) {
                    continue;
                }
                String name = model.name().strip();
                result.computeIfAbsent(name, ignored -> new PublicModel(name))
                        .merge(model.platform(), group.platform(), model.pricing(),
                                model.enableGroups());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Fills blank {@code capabilities_json} for VIDEO models so the admin list already shows a
     * default「最多参考图」after sync. Operator-edited JSON is left untouched.
     */
    private static void seedVideoCapabilitiesIfAbsent(LlmModelEntity model) {
        if (!LlmModelEntity.TYPE_VIDEO.equals(model.getModelType())) {
            return;
        }
        String existing = model.getCapabilitiesJson();
        if (existing != null && !existing.isBlank()) {
            return;
        }
        model.setCapabilitiesJson(VideoCapabilityDefaults.capabilitiesJson(model.getModelKey()));
    }

    /**
     * Resolves the catalogue type for one plaza model.
     *
     * <p>Configured overrides win first so names without reliable keywords (or false-positive
     * heuristics) can be pinned. Keyword heuristics cover common vendor tokens; anything else
     * defaults to chat.</p>
     */
    private String modelType(String modelKey, PublicModel model) {
        String override = properties.modelTypeOverride(modelKey);
        if (override != null) {
            return normalizeModelType(override);
        }
        String hint = ((model.platform() == null ? "" : model.platform())
                + " "
                + model.name()).toLowerCase(Locale.ROOT);
        NewApiModelPlaza.Pricing pricing = model.pricing();
        if ((pricing != null && (pricing.imageInputPrice() != null
                || pricing.imageOutputPrice() != null))
                || containsAny(hint, "image", "dall-e", "flux", "midjourney", "stable-diffusion")) {
            return LlmModelEntity.TYPE_IMAGE;
        }
        if (containsAny(hint,
                "video", "sora", "veo", "kling",
                "seedance", "wan", "hailuo", "runway", "luma", "minimax", "vidu", "pika")) {
            return LlmModelEntity.TYPE_VIDEO;
        }
        if (containsAny(hint, "audio", "tts", "whisper", "speech", "transcription")) {
            return LlmModelEntity.TYPE_AUDIO;
        }
        return LlmModelEntity.TYPE_CHAT;
    }

    private static String normalizeModelType(String type) {
        String normalised = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case LlmModelEntity.TYPE_CHAT,
                 LlmModelEntity.TYPE_IMAGE,
                 LlmModelEntity.TYPE_VIDEO,
                 LlmModelEntity.TYPE_AUDIO -> normalised;
            default -> LlmModelEntity.TYPE_CHAT;
        };
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String categoryOf(Set<String> modelTypes) {
        if (modelTypes.contains(LlmModelEntity.TYPE_CHAT)) {
            return LlmProviderEntity.CATEGORY_CHAT;
        }
        if (modelTypes.contains(LlmModelEntity.TYPE_IMAGE)) {
            return LlmProviderEntity.CATEGORY_IMAGE;
        }
        if (modelTypes.contains(LlmModelEntity.TYPE_VIDEO)) {
            return LlmProviderEntity.CATEGORY_VIDEO;
        }
        if (modelTypes.contains(LlmModelEntity.TYPE_AUDIO)) {
            return LlmProviderEntity.CATEGORY_AUDIO;
        }
        return LlmProviderEntity.CATEGORY_OTHER;
    }

    private static boolean hasExplicitPrice(NewApiModelPlaza.Pricing pricing) {
        if (pricing == null) {
            return false;
        }
        if (anyNonNull(
                pricing.inputPrice(),
                pricing.outputPrice(),
                pricing.cacheWritePrice(),
                pricing.cacheWrite1hPrice(),
                pricing.cacheReadPrice(),
                pricing.imageInputPrice(),
                pricing.imageOutputPrice(),
                pricing.perRequestPrice())) {
            return true;
        }
        return pricing.intervals().stream().anyMatch(interval -> interval != null
                && anyNonNull(
                interval.inputPrice(),
                interval.outputPrice(),
                interval.cacheWritePrice(),
                interval.cacheWrite1hPrice(),
                interval.cacheReadPrice(),
                interval.perRequestPrice()));
    }

    private static boolean anyNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private static String inferredCompany(String modelName) {
        String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        if (name.startsWith("gpt-") || name.startsWith("o1") || name.startsWith("o3")
                || name.startsWith("dall-e")) {
            return "OpenAI";
        }
        if (name.startsWith("claude")) {
            return "Anthropic";
        }
        if (name.startsWith("gemini")) {
            return "Google";
        }
        if (name.startsWith("deepseek")) {
            return "DeepSeek";
        }
        if (name.startsWith("qwen")) {
            return "Qwen";
        }
        if (name.startsWith("grok")) {
            return "xAI";
        }
        if (name.startsWith("glm")) {
            return "Zhipu AI";
        }
        if (name.startsWith("kimi")) {
            return "Moonshot AI";
        }
        int slash = modelName == null ? -1 : modelName.indexOf('/');
        return slash > 0 ? modelName.substring(0, slash) : null;
    }

    private static final class PublicModel {

        private final String name;
        private String platform;
        private NewApiModelPlaza.Pricing pricing;
        private final Set<String> groups = new LinkedHashSet<>();

        private PublicModel(String name) {
            this.name = name;
        }

        void merge(String modelPlatform,
                   String groupPlatform,
                   NewApiModelPlaza.Pricing discoveredPricing,
                   List<String> enableGroups) {
            if (platform == null || platform.isBlank()) {
                platform = modelPlatform != null && !modelPlatform.isBlank()
                        ? modelPlatform
                        : groupPlatform;
            }
            if (pricing == null && discoveredPricing != null) {
                pricing = discoveredPricing;
            }
            if (enableGroups != null) {
                for (String group : enableGroups) {
                    if (group != null && !group.isBlank()) {
                        groups.add(group.strip());
                    }
                }
            }
            if (groups.isEmpty() && groupPlatform != null && !groupPlatform.isBlank()) {
                groups.add(groupPlatform.strip());
            }
        }

        /** Comma separated relay groups, matching {@code llm_models.upstream_groups}. */
        String groupsCsv() {
            return groups.isEmpty() ? null : String.join(",", groups);
        }

        String name() {
            return name;
        }

        String platform() {
            return platform;
        }

        NewApiModelPlaza.Pricing pricing() {
            return pricing;
        }
    }
}

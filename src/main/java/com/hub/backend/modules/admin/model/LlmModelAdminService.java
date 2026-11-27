package com.hub.backend.modules.admin.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import com.hub.backend.modules.model.NewApiCatalogSyncService;
import com.hub.backend.modules.model.dto.ModelCapabilities;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelTagEntity;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.LlmModelTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read and presentation-management view for the New API-sourced model catalogue.
 *
 * <p>Create, update, delete and upstream discovery are intentionally absent: New API owns those
 * operations. The admin console can only hide/show a model and adjust fields that affect display
 * and local capability gates such as thinking.</p>
 */
@Service
@Transactional(readOnly = true)
public class LlmModelAdminService {

    private static final String THINKING_PARAMETER_PATTERN = "[A-Za-z_][A-Za-z0-9_]{0,63}";
    private static final Set<String> VIDEO_MODES = Set.of(
            "text2video", "image2video", "first-last-frame", "reference2video");
    private static final String ASPECT_RATIO_PATTERN = "[0-9]{1,2}:[0-9]{1,2}";

    /**
     * 分辨率标签，大小写都要收。
     *
     * <p>多数上游写小写 {@code 720p}，但 minimax 系列只认 {@code 768P} / {@code 1088P} /
     * {@code 1440P} —— 这是它们真实接受的取值，插件原样发出去才不会被 400。以前这里只匹配小写，
     * 于是这些模型的分辨率声明根本存不进去。</p>
     */
    private static final String RESOLUTION_PATTERN = "[0-9]{3,4}[pP]";

    /** Shown verbatim by the console; also the reason recorded in a batch's {@code skipped}. */
    private static final String STILL_AVAILABLE_MESSAGE =
            "该模型仍在上游提供，只有已下线的模型可以删除；如需停止使用请先隐藏";

    private final LlmModelRepository models;
    private final LlmModelGroupRepository groups;
    private final LlmModelTagRepository tags;
    private final LlmProviderRepository providers;
    private final NewApiCatalogSyncService catalogSync;
    private final LlmModelReferenceCleaner referenceCleaner;
    private final ObjectMapper objectMapper;

    public LlmModelAdminService(LlmModelRepository models,
                                LlmModelGroupRepository groups,
                                LlmModelTagRepository tags,
                                LlmProviderRepository providers,
                                NewApiCatalogSyncService catalogSync,
                                LlmModelReferenceCleaner referenceCleaner,
                                ObjectMapper objectMapper) {
        this.models = models;
        this.groups = groups;
        this.tags = tags;
        this.providers = providers;
        this.catalogSync = catalogSync;
        this.referenceCleaner = referenceCleaner;
        this.objectMapper = objectMapper;
    }

    public List<AdminModelResponse> list(String modelType, String status, Long providerId) {
        return list(modelType, status, null, providerId);
    }

    /**
     * @param availability {@code ONLINE} / {@code OFFLINE} to filter on
     *                     {@code upstream_available}, {@code null} for both
     */
    public List<AdminModelResponse> list(String modelType, String status,
                                         String availability, Long providerId) {
        String typeFilter = normalize(modelType);
        String statusFilter = normalize(status);
        String availabilityFilter = normalize(availability);
        List<LlmModelEntity> ordered = models.findAll().stream()
                .filter(model -> LlmModelEntity.SOURCE_NEW_API.equals(model.getSource()))
                .filter(model -> typeFilter == null || typeFilter.equals(model.getModelType()))
                .filter(model -> statusFilter == null
                        || statusFilter.equals(normalize(model.getStatus())))
                // "OFFLINE" is the only value that means "retired upstream"; anything else non-null
                // keeps the live rows, so an unknown value degrades to ONLINE rather than 400.
                .filter(model -> availabilityFilter == null
                        || ("OFFLINE".equals(availabilityFilter) != model.isUpstreamAvailable()))
                .filter(model -> providerId == null || providerId.equals(model.getProviderId()))
                .sorted(Comparator.comparingInt(LlmModelEntity::getSortOrder)
                        .thenComparing(LlmModelEntity::getId))
                .toList();
        return responsesFor(ordered);
    }

    public AdminModelResponse detail(Long id) {
        LlmModelEntity model = requireModel(id);
        return responsesFor(List.of(model)).get(0);
    }

    /**
     * Builds responses for many rows with one provider lookup, one group lookup and one tag query.
     *
     * <p>Calling {@link #detail(Long)} in a loop would issue three queries per model, which turns a
     * 100-row batch into 300 round trips — the very cost the batch endpoint exists to remove.</p>
     */
    private List<AdminModelResponse> responsesFor(List<LlmModelEntity> modelList) {
        Map<Long, LlmProviderEntity> providersById = providers.findAll().stream()
                .collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
        Map<Long, LlmModelGroupEntity> groupsById = groups.findAll().stream()
                .collect(Collectors.toMap(LlmModelGroupEntity::getId, Function.identity()));
        Map<Long, List<String>> tagsByModel = tagsByModel(modelList);
        return modelList.stream()
                .map(model -> toResponse(model, providersById, groupsById, tagsByModel))
                .toList();
    }

    /**
     * Refreshes the local cache from New API. Both the provider and model pages can call this
     * operation; the two catalogues are always refreshed together so their foreign keys stay
     * consistent.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NewApiCatalogSyncResponse sync() {
        return catalogSync.sync();
    }

    @Transactional
    public AdminModelResponse updateVisibility(Long id, AdminModelVisibilityRequest request) {
        LlmModelEntity model = requireModel(id);
        model.setStatus(normalizeModelStatus(request.status()));
        models.save(model);
        return detail(model.getId());
    }

    /**
     * Updates only fields that control presentation and local capability gates. The upstream
     * model key and route stay read-only because changing them would break the New API mapping.
     *
     * <p>{@code videoCapabilities} is deliberately written here and not inside
     * {@link #applyDisplayFields}: the batch endpoint shares that helper, and a half-written
     * capabilities object from a batch row would be far harder to diagnose than it is to keep the
     * two paths apart.</p>
     */
    @Transactional
    public AdminModelResponse updateDisplay(Long id, AdminModelDisplayRequest request) {
        LlmModelEntity model = requireModel(id);
        // modelType first so a same-request videoCapabilities write sees the new type.
        applyModelType(model, request.modelType());
        applyDisplayFields(model, request);
        if (request.videoCapabilities() != null) {
            applyVideoCapabilities(model, request.videoCapabilities());
        }
        models.save(model);
        return detail(model.getId());
    }

    /**
     * Applies many rows in one transaction.
     *
     * <p>All-or-nothing on purpose: a missing {@code modelId} is a caller bug, not a business
     * condition to skip quietly, and a half-applied visibility change is harder to diagnose than a
     * clean failure. Duplicated ids are rejected instead of silently merged, because the second
     * entry would otherwise be lost without the caller noticing.</p>
     */
    @Transactional
    public AdminModelBatchUpdateResponse batchUpdate(AdminModelBatchUpdateRequest request) {
        Map<Long, AdminModelBatchUpdateRequest.Item> byId = new LinkedHashMap<>();
        for (AdminModelBatchUpdateRequest.Item item : request.items()) {
            if (byId.putIfAbsent(item.modelId(), item) != null) {
                throw new InvalidRequestException(
                        "duplicated modelId %d in batch".formatted(item.modelId()));
            }
        }
        // Resolve every row before touching any of them: a 404 must not depend on list order, and
        // the caller should see the missing id while the transaction still has no side effect.
        Map<Long, LlmModelEntity> modelsById = new LinkedHashMap<>();
        for (Long modelId : byId.keySet()) {
            modelsById.put(modelId, requireModel(modelId));
        }
        List<LlmModelEntity> updated = new ArrayList<>(byId.size());
        for (Map.Entry<Long, AdminModelBatchUpdateRequest.Item> entry : byId.entrySet()) {
            LlmModelEntity model = modelsById.get(entry.getKey());
            AdminModelBatchUpdateRequest.Item item = entry.getValue();
            if (item.status() != null) {
                model.setStatus(normalizeModelStatus(item.status()));
            }
            applyDisplayFields(model, item);
            updated.add(model);
        }
        models.saveAll(updated);
        models.flush();
        List<AdminModelResponse> responses = responsesFor(updated);
        return new AdminModelBatchUpdateResponse(byId.size(), responses.size(), responses);
    }

    /**
     * Writes only the non-{@code null} presentation fields of a request onto a model.
     *
     * <p>Shared by the single and the batch endpoint so the two can never drift: empty strings
     * clear {@code company} / {@code iconUrl} / {@code description}, and
     * {@code thinkingParameter} keeps going through {@link #applyThinkingParameter} for its
     * identifier check and its "empty means remove" rule.</p>
     */
    private void applyDisplayFields(LlmModelEntity model, AdminModelDisplayRequest request) {
        applyDisplayFields(model, new AdminModelBatchUpdateRequest.Item(
                model.getId(),
                null,
                request.modelName(),
                request.company(),
                request.iconUrl(),
                request.description(),
                request.sortOrder(),
                request.thinking(),
                request.thinkingParameter(),
                request.thinkingOnValue(),
                request.thinkingOffValue(),
                request.supportsWebSearch()));
    }

    private void applyDisplayFields(LlmModelEntity model, AdminModelBatchUpdateRequest.Item item) {
        if (item.modelName() != null) {
            String name = item.modelName().strip();
            if (name.isEmpty()) {
                throw new InvalidRequestException("modelName must not be blank");
            }
            model.setModelName(name);
        }
        if (item.company() != null) {
            model.setCompany(blankToNull(item.company()));
        }
        if (item.iconUrl() != null) {
            model.setIconUrl(blankToNull(item.iconUrl()));
        }
        if (item.description() != null) {
            model.setDescription(blankToNull(item.description()));
        }
        if (item.sortOrder() != null) {
            model.setSortOrder(item.sortOrder());
        }
        if (item.thinking() != null) {
            model.setThinking(item.thinking());
        }
        if (item.thinkingParameter() != null) {
            applyThinkingParameter(model, item.thinkingParameter());
        }
        if (item.thinkingOnValue() != null) {
            applyThinkingValue(model, "thinkingOnValue", item.thinkingOnValue());
        }
        if (item.thinkingOffValue() != null) {
            applyThinkingValue(model, "thinkingOffValue", item.thinkingOffValue());
        }
        if (item.supportsWebSearch() != null) {
            model.setSupportsWebSearch(item.supportsWebSearch());
        }
    }

    private void applyModelType(LlmModelEntity model, String modelType) {
        if (modelType == null) {
            return;
        }
        String normalised = modelType.strip().toUpperCase(Locale.ROOT);
        if (!Set.of(
                LlmModelEntity.TYPE_CHAT,
                LlmModelEntity.TYPE_IMAGE,
                LlmModelEntity.TYPE_VIDEO,
                LlmModelEntity.TYPE_AUDIO).contains(normalised)) {
            throw new InvalidRequestException(
                    "modelType must be CHAT, IMAGE, VIDEO or AUDIO");
        }
        model.setModelType(normalised);
    }

    private void applyVideoCapabilities(LlmModelEntity model, AdminVideoCapabilitiesRequest request) {
        if (!LlmModelEntity.TYPE_VIDEO.equals(model.getModelType())) {
            throw new InvalidRequestException("videoCapabilities can only be set on VIDEO models");
        }
        List<String> modes = normalizeUniqueStrings(request.modes(), "modes");
        if (modes.isEmpty()) {
            throw new InvalidRequestException("videoCapabilities.modes must not be empty");
        }
        for (String mode : modes) {
            if (!VIDEO_MODES.contains(mode)) {
                throw new InvalidRequestException(
                        "unsupported video mode '" + mode
                                + "'; expected text2video, image2video, first-last-frame or reference2video");
            }
        }
        List<String> aspectRatios = normalizeUniqueStrings(request.aspectRatios(), "aspectRatios");
        for (String ratio : aspectRatios) {
            if (!ratio.matches(ASPECT_RATIO_PATTERN)) {
                throw new InvalidRequestException(
                        "aspectRatio must look like 16:9, got '" + ratio + "'");
            }
        }
        List<String> resolutions = normalizeUniqueStrings(request.resolutions(), "resolutions");
        for (String resolution : resolutions) {
            if (!resolution.matches(RESOLUTION_PATTERN)) {
                throw new InvalidRequestException(
                        "resolution must look like 720p, got '" + resolution + "'");
            }
        }
        List<Integer> durations = normalizeUniqueInts(request.durations(), "durations");
        for (Integer duration : durations) {
            if (duration == null || duration < 1 || duration > 60) {
                throw new InvalidRequestException("duration must be between 1 and 60 seconds");
            }
        }
        String durationMode = request.durationMode() == null
                ? ModelCapabilities.DURATION_MODE_RANGE
                : request.durationMode().strip().toLowerCase(Locale.ROOT);
        if (!ModelCapabilities.DURATION_MODE_RANGE.equals(durationMode)
                && !ModelCapabilities.DURATION_MODE_SET.equals(durationMode)) {
            throw new InvalidRequestException("durationMode must be range or set");
        }
        int maxReferenceImages = request.maxReferenceImages() == null
                ? 0
                : request.maxReferenceImages();
        boolean audio = request.audio() != null && request.audio();

        try {
            ObjectNode root = readCapabilitiesObject(model.getCapabilitiesJson());
            root.set("modes", stringArray(modes));
            root.set("aspectRatios", stringArray(aspectRatios));
            root.set("resolutions", stringArray(resolutions));
            root.set("durations", intArray(durations));
            root.put("durationMode", durationMode);
            root.put("maxReferenceImages", maxReferenceImages);
            root.put("audio", audio);
            model.setCapabilitiesJson(objectMapper.writeValueAsString(root));
        } catch (InvalidRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidRequestException("capabilities_json is not valid JSON");
        }
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private ArrayNode intArray(List<Integer> values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Integer value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> normalizeUniqueStrings(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            String value = raw.strip();
            if (value.isEmpty()) {
                throw new InvalidRequestException(field + " must not contain blank values");
            }
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private static List<Integer> normalizeUniqueInts(List<Integer> values, String field) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        for (Integer value : values) {
            if (value == null) {
                throw new InvalidRequestException(field + " must not contain null values");
            }
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private void applyThinkingParameter(LlmModelEntity model, String rawParameter) {
        String parameter = rawParameter.strip();
        if (parameter.isEmpty()) {
            model.setCapabilitiesJson(removeThinkingConfig(model.getCapabilitiesJson()));
            return;
        }
        if (!parameter.matches(THINKING_PARAMETER_PATTERN)) {
            throw new InvalidRequestException(
                    "thinkingParameter must be a simple identifier such as enable_thinking");
        }
        model.setCapabilitiesJson(putCapabilityText(
                model.getCapabilitiesJson(), "thinkingParameter", parameter));
    }

    private void applyThinkingValue(LlmModelEntity model, String field, String rawValue) {
        String value = rawValue.strip();
        if (value.isEmpty()) {
            model.setCapabilitiesJson(removeCapabilityField(model.getCapabilitiesJson(), field));
            return;
        }
        model.setCapabilitiesJson(putCapabilityText(model.getCapabilitiesJson(), field, value));
    }

    private String putCapabilityText(String capabilitiesJson, String field, String value) {
        try {
            ObjectNode root = readCapabilitiesObject(capabilitiesJson);
            root.put(field, value);
            return objectMapper.writeValueAsString(root);
        } catch (InvalidRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidRequestException("capabilities_json is not valid JSON");
        }
    }

    private String removeThinkingConfig(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            return null;
        }
        try {
            ObjectNode root = readCapabilitiesObject(capabilitiesJson);
            root.remove("thinkingParameter");
            root.remove("thinkingOnValue");
            root.remove("thinkingOffValue");
            if (root.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(root);
        } catch (InvalidRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidRequestException("capabilities_json is not valid JSON");
        }
    }

    private String removeCapabilityField(String capabilitiesJson, String field) {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            return null;
        }
        try {
            ObjectNode root = readCapabilitiesObject(capabilitiesJson);
            root.remove(field);
            if (root.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(root);
        } catch (InvalidRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidRequestException("capabilities_json is not valid JSON");
        }
    }

    private ObjectNode readCapabilitiesObject(String capabilitiesJson) throws Exception {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode parsed = objectMapper.readTree(capabilitiesJson);
        if (parsed == null || !parsed.isObject()) {
            throw new InvalidRequestException("capabilities_json must be a JSON object");
        }
        return (ObjectNode) parsed;
    }

    private LlmModelEntity requireModel(Long id) {
        return models.findById(id)
                .filter(model -> LlmModelEntity.SOURCE_NEW_API.equals(model.getSource()))
                .orElseThrow(() -> new NotFoundException("Model %d was not found".formatted(id)));
    }

    /**
     * Deletes one retired model.
     *
     * <p>Strict on purpose: this is "delete exactly this one", so a row that is still served
     * upstream is a 409 rather than something to skip quietly. Deleting a live model would not even
     * stick — the next catalogue sync recreates it under a new primary key while it is missing from
     * the front end, which is far harder to diagnose than a refusal.</p>
     */
    @Transactional
    public void deleteOfflineModel(Long id) {
        LlmModelEntity model = requireModel(id);
        if (model.isUpstreamAvailable()) {
            throw new ConflictException(STILL_AVAILABLE_MESSAGE);
        }
        referenceCleaner.detach(List.of(id));
        models.delete(model);
        models.flush();
    }

    /**
     * Purges many retired models in one transaction.
     *
     * <p>Partial success: models still served upstream are reported in {@code skipped} with a
     * reason, and ids that are already gone are skipped too — that makes a retry safe. Anything else
     * would let one model that upstream restored drag the other ninety-nine down with it.</p>
     */
    @Transactional
    public AdminModelPurgeResponse purgeOfflineModels(AdminModelPurgeRequest request) {
        List<Long> ids = request.distinctModelIds();
        if (ids.isEmpty()) {
            throw new InvalidRequestException("modelIds must not be empty");
        }

        Map<Long, LlmModelEntity> found = models.findAllById(ids).stream()
                .filter(model -> LlmModelEntity.SOURCE_NEW_API.equals(model.getSource()))
                .collect(Collectors.toMap(LlmModelEntity::getId, Function.identity()));

        List<Long> deletable = new ArrayList<>(ids.size());
        List<AdminModelPurgeResponse.Skipped> skipped = new ArrayList<>();
        for (Long id : ids) {
            LlmModelEntity model = found.get(id);
            if (model == null) {
                skipped.add(new AdminModelPurgeResponse.Skipped(id, "模型已不存在"));
            } else if (model.isUpstreamAvailable()) {
                skipped.add(new AdminModelPurgeResponse.Skipped(id, STILL_AVAILABLE_MESSAGE));
            } else {
                deletable.add(id);
            }
        }

        if (!deletable.isEmpty()) {
            // References first: llm_models has ten inbound columns and only one cascades.
            referenceCleaner.detach(deletable);
            // deleteAll(Iterable) instead of deleteAllByIdInBatch: the latter issues one statement
            // but leaves the entities managed, so the same transaction would still read them back.
            models.deleteAll(deletable.stream().map(found::get).toList());
            models.flush();
        }
        return new AdminModelPurgeResponse(ids.size(), deletable.size(), skipped);
    }

    private Map<Long, List<String>> tagsByModel(List<LlmModelEntity> modelList) {
        if (modelList.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = modelList.stream().map(LlmModelEntity::getId).toList();
        return tags.findByModelIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(
                        LlmModelTagEntity::getModelId,
                        LinkedHashMap::new,
                        Collectors.mapping(LlmModelTagEntity::getTag, Collectors.toList())));
    }

    private AdminModelResponse toResponse(
            LlmModelEntity model,
            Map<Long, LlmProviderEntity> providersById,
            Map<Long, LlmModelGroupEntity> groupsById,
            Map<Long, List<String>> tagsByModel) {
        LlmProviderEntity provider = model.getProviderId() == null
                ? null
                : providersById.get(model.getProviderId());
        LlmModelGroupEntity group = model.getGroupId() == null
                ? null
                : groupsById.get(model.getGroupId());
        ModelCapabilities capabilities = ModelCapabilities.parse(
                model.getCapabilitiesJson(), objectMapper);
        String thinkingParameter = capabilities == null ? null : capabilities.thinkingParameter();
        String thinkingOnValue = capabilities == null ? null : capabilities.thinkingOnValue();
        String thinkingOffValue = capabilities == null ? null : capabilities.thinkingOffValue();
        return new AdminModelResponse(
                model.getId(),
                model.getSource(),
                model.getExternalId(),
                model.getModelKey(),
                model.getModelName(),
                model.getModelType(),
                model.getCompany(),
                model.resolvedRoute(),
                model.getStatus(),
                model.isUpstreamAvailable(),
                model.getSiteId(),
                model.getGroupId(),
                group == null ? null : group.getGroupKey(),
                model.getProviderId(),
                provider == null ? null : provider.getProviderKey(),
                provider == null ? null : provider.getName(),
                model.getIconUrl(),
                model.getDescription(),
                tagsByModel.getOrDefault(model.getId(), List.of()),
                model.isFree(),
                model.isThinking(),
                thinkingParameter,
                thinkingOnValue,
                thinkingOffValue,
                model.getConsumePoints(),
                model.getContextWindow(),
                model.getMaxOutputTokens(),
                model.isSupportsVision(),
                model.isSupportsTools(),
                model.isSupportsWebSearch(),
                model.getInputPointsPer1k(),
                model.getOutputPointsPer1k(),
                model.getSortOrder(),
                model.getUpdatedAt(),
                capabilities);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Validates the local visibility switch. Kept in one place so the single and the batch endpoint
     * accept exactly the same values.
     */
    private static String normalizeModelStatus(String value) {
        String status = normalize(value);
        if (!LlmModelEntity.STATUS_ACTIVE.equals(status)
                && !LlmModelEntity.STATUS_DISABLED.equals(status)) {
            throw new InvalidRequestException("status must be ACTIVE or DISABLED");
        }
        return status;
    }
}

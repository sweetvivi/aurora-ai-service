package com.hub.backend.modules.model;

import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.model.dto.ModelCatalogResponse;
import com.hub.backend.modules.model.dto.ModelFamilyCatalogResponse;
import com.hub.backend.modules.model.dto.ModelFamilyResponse;
import com.hub.backend.modules.model.dto.ModelGroupResponse;
import com.hub.backend.modules.model.dto.ModelSummaryResponse;
import com.hub.backend.modules.model.entity.LlmModelBrandEntity;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelTagEntity;
import com.hub.backend.modules.model.repository.LlmModelBrandRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.LlmModelTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read model of the public catalogue.
 *
 * <p>Only display fields leave this class. The entity carries {@code route} — the provider specific
 * model name — and {@link ModelSummaryResponse} simply has no field for it, so the upstream
 * configuration cannot leak through the catalogue by accident.</p>
 *
 * <p>Visibility is {@code status = ACTIVE AND (site_id IS NULL OR site_id = <resolved site>)}: rows
 * with no site are global, the rest belong to one white label site.</p>
 */
@Service
@Transactional(readOnly = true)
public class ModelCatalogService {

    /** Unconfigured families sort after every configured brand. */
    private static final int UNCONFIGURED_SORT_ORDER = 1_000_000;

    private final LlmModelRepository modelRepository;
    private final LlmModelTagRepository tagRepository;
    private final LlmModelBrandRepository brandRepository;
    private final ModelFallbackCatalogService fallbackCatalog;
    private final ModelCapabilityPublisher capabilityPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ModelCatalogService(LlmModelRepository modelRepository,
                               LlmModelTagRepository tagRepository,
                               LlmModelBrandRepository brandRepository,
                               ModelFallbackCatalogService fallbackCatalog,
                               ModelCapabilityPublisher capabilityPublisher,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.modelRepository = modelRepository;
        this.tagRepository = tagRepository;
        this.brandRepository = brandRepository;
        this.fallbackCatalog = fallbackCatalog;
        this.capabilityPublisher = capabilityPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * @param groupKey optional filter; an unknown key yields an empty catalogue rather than an
     *                 error, because a filter matching nothing is not a failure
     */
    public ModelCatalogResponse catalog(Long siteId, String groupKey) {
        return catalog(siteId, groupKey, null);
    }

    /**
     * @param groupKey   optional model-version group filter
     * @param modelType  optional {@code CHAT} / {@code IMAGE} / {@code VIDEO} / {@code AUDIO} filter,
     *                   so a chat picker does not offer image models and vice versa
     */
    public ModelCatalogResponse catalog(Long siteId, String groupKey, String modelType) {
        String filter = groupKey == null || groupKey.isBlank() ? null : groupKey.strip();
        String type = normalizeType(modelType);

        List<ModelFallbackCatalogService.GroupView> groups = fallbackCatalog
                .visibleGroups(siteId, type)
                .stream()
                .filter(group -> filter == null || filter.equals(group.group().getGroupKey()))
                .toList();
        List<ModelSummaryResponse> summaries = groups.stream()
                .map(this::summaryOf)
                .toList();

        return new ModelCatalogResponse(groupResponses(groups), summaries, summaries.size());
    }

    /**
     * The same visible models, folded into families by vendor.
     *
     * <p>A family is a derived vendor view over the public model-version groups. Clients that
     * present a model picker need this shape, and building it here keeps them from assembling it
     * from the groups and models arrays themselves.</p>
     *
     * @param modelType optional {@code CHAT} / {@code IMAGE} / {@code VIDEO} / {@code AUDIO} filter.
     *                  It is applied through the same helper as the catalogue's, because a picker
     *                  fed by families and a picker fed by models must not disagree about what a
     *                  type filter means.
     */
    public ModelFamilyCatalogResponse families(Long siteId, String modelType) {
        List<ModelSummaryResponse> summaries = visibleModels(siteId, modelType);
        BrandIndex brands = BrandIndex.load(brandRepository);

        // Insertion ordered so unconfigured families keep catalogue order as a tie-break.
        Map<String, List<ModelSummaryResponse>> byFamily = new LinkedHashMap<>();
        Map<String, String> fallbackNames = new LinkedHashMap<>();
        Map<String, LlmModelBrandEntity> brandByFamily = new LinkedHashMap<>();
        for (ModelSummaryResponse summary : summaries) {
            LlmModelBrandEntity brand = brands.resolve(summary.company());
            String familyKey = brand != null
                    ? brand.getBrandKey()
                    : ModelBrandKeys.familyKeyOf(summary.company());
            byFamily.computeIfAbsent(familyKey, key -> new ArrayList<>()).add(summary);
            fallbackNames.putIfAbsent(familyKey, displayNameOf(summary.company()));
            if (brand != null) {
                brandByFamily.putIfAbsent(familyKey, brand);
            }
        }

        List<ModelFamilyResponse> families = byFamily.entrySet().stream()
                .map(entry -> {
                    LlmModelBrandEntity brand = brandByFamily.get(entry.getKey());
                    if (brand != null) {
                        return new ModelFamilyResponse(
                                brand.getBrandKey(),
                                brand.getDisplayName(),
                                brand.getIconUrl(),
                                brand.getDescription(),
                                brand.getSortOrder(),
                                true,
                                entry.getValue().size(),
                                entry.getValue());
                    }
                    return new ModelFamilyResponse(
                            entry.getKey(),
                            fallbackNames.get(entry.getKey()),
                            null,
                            null,
                            UNCONFIGURED_SORT_ORDER,
                            false,
                            entry.getValue().size(),
                            entry.getValue());
                })
                .sorted(Comparator
                        .comparingInt(ModelFamilyResponse::sortOrder)
                        .thenComparing(ModelFamilyResponse::familyKey))
                .toList();

        return new ModelFamilyCatalogResponse(families, families.size(), summaries.size());
    }

    /** @see #families(Long, String) */
    public ModelFamilyCatalogResponse families(Long siteId) {
        return families(siteId, null);
    }

    /**
     * Visible models, optionally narrowed to a single model type.
     *
     * <p>Both the catalogue and the families view go through here so that "which models are
     * visible to this site" and "what does a type filter mean" each have one answer. This table has
     * already been read by two call sites under different rules once (see {@code docs/}); the
     * second copy is not worth the risk.</p>
     */
    private List<ModelSummaryResponse> visibleModels(Long siteId, String modelType) {
        return fallbackCatalog.visibleGroups(siteId, normalizeType(modelType)).stream()
                .map(this::summaryOf)
                .toList();
    }

    private ModelSummaryResponse summaryOf(ModelFallbackCatalogService.GroupView view) {
        LlmModelEntity representative = view.candidates().get(0).model();
        List<LlmModelEntity> representatives = view.candidates().stream()
                .map(ModelFallbackCatalogService.Candidate::model)
                .toList();
        Map<Long, List<String>> tagsByModel = tagsOf(representatives);
        return ModelSummaryResponse.ofGroup(
                view.group().getGroupKey(),
                view.group().getDisplayName(),
                representative,
                view.group().getGroupKey(),
                representative.getConsumePoints(),
                tagsByModel.getOrDefault(representative.getId(), List.of()),
                groupCapabilitiesOf(view));
    }

    /** @return the declaration the catalogue publishes for one version group */
    private com.hub.backend.modules.model.dto.ModelCapabilities groupCapabilitiesOf(
            ModelFallbackCatalogService.GroupView view) {
        return capabilityPublisher.publishedFor(view);
    }

    private static String displayNameOf(String company) {
        return company == null || company.isBlank() ? "其他" : company.strip();
    }

    /**
     * Active brands indexed for company → brand resolution.
     *
     * <p>Match order: exact display name (case-insensitive, covers CJK company names), then
     * company slug == {@code brand_key}.</p>
     */
    private static final class BrandIndex {
        private final Map<String, LlmModelBrandEntity> byKey;
        private final Map<String, LlmModelBrandEntity> byDisplayName;

        private BrandIndex(Map<String, LlmModelBrandEntity> byKey,
                           Map<String, LlmModelBrandEntity> byDisplayName) {
            this.byKey = byKey;
            this.byDisplayName = byDisplayName;
        }

        static BrandIndex load(LlmModelBrandRepository brandRepository) {
            List<LlmModelBrandEntity> active = brandRepository
                    .findByStatusOrderBySortOrderAscIdAsc(LlmModelBrandEntity.STATUS_ACTIVE);
            Map<String, LlmModelBrandEntity> byKey = new LinkedHashMap<>();
            Map<String, LlmModelBrandEntity> byDisplayName = new LinkedHashMap<>();
            for (LlmModelBrandEntity brand : active) {
                byKey.putIfAbsent(brand.getBrandKey(), brand);
                byDisplayName.putIfAbsent(
                        brand.getDisplayName().strip().toLowerCase(Locale.ROOT), brand);
            }
            return new BrandIndex(byKey, byDisplayName);
        }

        LlmModelBrandEntity resolve(String company) {
            if (company == null || company.isBlank()) {
                return null;
            }
            String trimmed = company.strip();
            LlmModelBrandEntity byName = byDisplayName.get(trimmed.toLowerCase(Locale.ROOT));
            if (byName != null) {
                return byName;
            }
            return byKey.get(ModelBrandKeys.familyKeyOf(trimmed));
        }
    }


    public ModelSummaryResponse model(Long siteId, String modelKey) {
        ModelFallbackCatalogService.GroupView group = fallbackCatalog
                .findVisibleGroup(siteId, modelKey, null)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Model '%s' was not found".formatted(modelKey)));

        return summaryOf(group);
    }

    /**
     * Resolves the provider-specific model name for an execution path without adding it to the
     * public catalogue DTO.
     *
     * <p>Smart-editing clients need both names: {@code modelKey} is the stable identifier used to
     * call this backend, while {@code route} is what must be sent to the configured upstream. Keep
     * this lookup here rather than exposing the entity, so the public model endpoint cannot start
     * leaking supplier configuration through a future DTO change.</p>
     *
     * @param modelType optional type guard; a key belonging to another type falls back to itself
     * @return the resolved upstream route, or the supplied key when it is not visible in this type
     */
    public String resolvedRoute(Long siteId, String modelKey, String modelType) {
        if (modelKey == null || modelKey.isBlank()) {
            return modelKey;
        }

        return modelRepository.findVisibleByModelKey(siteId, modelKey, LlmModelEntity.STATUS_ACTIVE)
                .stream()
                .filter(model -> modelType == null || modelType.isBlank()
                        || modelType.equalsIgnoreCase(model.getModelType()))
                .findFirst()
                .map(LlmModelEntity::resolvedRoute)
                .orElse(modelKey);
    }

    /**
     * Public model-version groups in fallback-catalogue display order.
     *
     * <p>The catalogue intentionally models each version group as one storefront entry. Concrete
     * fallback members are not exposed as separate catalogue items.</p>
     */
    private List<ModelGroupResponse> groupResponses(
            List<ModelFallbackCatalogService.GroupView> groups) {
        return groups.stream()
                .map(group -> new ModelGroupResponse(
                        group.group().getGroupKey(),
                        group.group().getDisplayName(),
                        group.candidates().get(0).model().getCompany(),
                        1))
                .toList();
    }

    private Map<Long, List<String>> tagsOf(List<LlmModelEntity> models) {
        if (models.isEmpty()) {
            return Map.of();
        }
        List<Long> modelIds = models.stream().map(LlmModelEntity::getId).toList();

        return tagRepository.findByModelIdInOrderBySortOrderAscIdAsc(modelIds).stream()
                .collect(Collectors.groupingBy(
                        LlmModelTagEntity::getModelId,
                        LinkedHashMap::new,
                        Collectors.mapping(LlmModelTagEntity::getTag, Collectors.toList())));
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank()
                ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }
}

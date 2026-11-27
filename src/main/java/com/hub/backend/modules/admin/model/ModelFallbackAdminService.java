package com.hub.backend.modules.admin.model;

import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.price.ModelPriceService;
import com.hub.backend.modules.model.price.ModelPriceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin-side lifecycle for public model-version groups.
 *
 * <p>The group table is not a vendor or channel grouping. It is the public abstraction through
 * which interchangeable models of the same type can be tried in order. Consequently members can be
 * from different product families, but their type, visibility scope and upstream availability must
 * agree before they can be persisted.</p>
 */
@Service
@Transactional(readOnly = true)
public class ModelFallbackAdminService {

    /** Shared reason text so the console can match on it, and so retries read consistently. */
    private static final String NOT_A_MEMBER_REASON = "not a member of this group";

    private final LlmModelFallbackGroupRepository groups;
    private final LlmModelFallbackMemberRepository members;
    private final LlmModelRepository models;
    private final AppSiteRepository sites;
    private final ModelPriceService modelPrices;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ModelFallbackAdminService(LlmModelFallbackGroupRepository groups,
                                     LlmModelFallbackMemberRepository members,
                                     LlmModelRepository models,
                                     AppSiteRepository sites,
                                     ModelPriceService modelPrices,
                                     com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.groups = groups;
        this.members = members;
        this.models = models;
        this.sites = sites;
        this.modelPrices = modelPrices;
        this.objectMapper = objectMapper;
    }

    public List<AdminModelFallbackGroupResponse> list(String modelType,
                                                      Long siteId,
                                                      Boolean globalOnly) {
        String type = normalizeTypeFilter(modelType);
        List<LlmModelFallbackGroupEntity> rows;
        if (Boolean.TRUE.equals(globalOnly)) {
            rows = groups.findBySiteIdIsNullOrderBySortOrderAscIdAsc();
        } else if (siteId != null) {
            rows = groups.findBySiteIdOrderBySortOrderAscIdAsc(siteId);
        } else {
            rows = groups.findAllByOrderBySortOrderAscIdAsc();
        }
        return rows.stream()
                .filter(group -> type == null || type.equals(group.getModelType()))
                .map(this::detailOf)
                .toList();
    }

    public AdminModelFallbackGroupResponse detail(Long id) {
        return detailOf(requireGroup(id));
    }

    @Transactional
    public AdminModelFallbackGroupResponse create(AdminModelFallbackGroupRequest request) {
        String groupKey = normalizeGroupKey(request.groupKey());
        String modelType = normalizeType(request.modelType());
        validateSite(request.siteId());
        requireUniqueGroupKey(request.siteId(), groupKey, null);

        LlmModelFallbackGroupEntity group = new LlmModelFallbackGroupEntity(
                request.siteId(),
                groupKey,
                requiredText(request.displayName(), "displayName"),
                modelType);
        group.setStatus(normalizeStatus(request.status(), "ACTIVE"));
        group.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        group.setOrderCustomized(false);
        applyDurationOverride(group, request);
        return detailOf(groups.saveAndFlush(group));
    }

    @Transactional
    public AdminModelFallbackGroupResponse update(Long id,
                                                   AdminModelFallbackGroupRequest request) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        String groupKey = normalizeGroupKey(request.groupKey());
        String modelType = normalizeType(request.modelType());
        validateSite(request.siteId());
        requireUniqueGroupKey(request.siteId(), groupKey, id);

        group.setSiteId(request.siteId());
        group.setGroupKey(groupKey);
        group.setDisplayName(requiredText(request.displayName(), "displayName"));
        group.setModelType(modelType);
        group.setStatus(normalizeStatus(request.status(), group.getStatus()));
        group.setSortOrder(request.sortOrder() == null
                ? group.getSortOrder() : request.sortOrder());
        applyDurationOverride(group, request);

        for (LlmModelFallbackMemberEntity member :
                members.findByGroupIdOrderByPriorityAscIdAsc(group.getId())) {
            LlmModelEntity model = requireModel(member.getModelId());
            String problem = memberProblem(group, model);
            if (problem != null) {
                throw new InvalidRequestException(
                        "Cannot change group because member '%s' is no longer valid: %s"
                                .formatted(model.getModelKey(), problem));
            }
        }
        return detailOf(groups.save(group));
    }

    @Transactional
    public void delete(Long id) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        members.deleteByGroupId(id);
        groups.delete(group);
    }

    @Transactional
    public AdminModelFallbackGroupResponse addMember(
            Long id,
            AdminModelFallbackMemberRequest request) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        LlmModelEntity model = requireModel(request.modelId());
        requireMemberCanJoin(group, model);
        if (members.findByGroupIdAndModelId(id, model.getId()).isPresent()) {
            throw new ConflictException(
                    "Model %d is already a member of fallback group %d"
                            .formatted(model.getId(), id));
        }

        List<LlmModelFallbackMemberEntity> existing =
                members.findByGroupIdOrderByPriorityAscIdAsc(id);
        int nextPriority = existing.stream()
                .mapToInt(LlmModelFallbackMemberEntity::getPriority)
                .max()
                .orElse(-1) + 1;
        LlmModelFallbackMemberEntity member =
                new LlmModelFallbackMemberEntity(id, model.getId(), nextPriority);
        member.setEnabled(request.enabled() == null || request.enabled());
        members.save(member);
        return detailOf(group);
    }

    @Transactional
    public AdminModelFallbackGroupResponse removeMember(Long id, Long modelId) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        LlmModelFallbackMemberEntity target =
                members.findByGroupIdAndModelId(id, modelId)
                        .orElseThrow(() -> new NotFoundException(
                                "Model %d is not a member of fallback group %d"
                                        .formatted(modelId, id)));
        members.delete(target);
        members.flush();
        reindexRemainingMembers(id);
        return detailOf(group);
    }

    @Transactional
    public AdminModelFallbackGroupResponse setMemberEnabled(
            Long id,
            Long modelId,
            AdminModelFallbackMemberEnabledRequest request) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        LlmModelFallbackMemberEntity member =
                members.findByGroupIdAndModelId(id, modelId)
                        .orElseThrow(() -> new NotFoundException(
                                "Model %d is not a member of fallback group %d"
                                        .formatted(modelId, id)));
        if (request.enabled()) {
            requireMemberCanJoin(group, requireModel(modelId));
        }
        member.setEnabled(request.enabled());
        members.save(member);
        return detailOf(group);
    }

    /**
     * Switches many members on or off in one transaction.
     *
     * <p>Partial success by design: "upstream went offline" and "type no longer matches" are real
     * business states, and one such member must not sink the other 99. Those land in
     * {@code skipped} with the reason {@code memberProblem} already produces; a member that is
     * already in the target state counts as {@code unchanged} (success), and an id that is not a
     * member at all is skipped rather than a 404, because a concurrent removal elsewhere should not
     * fail the whole batch.</p>
     */
    @Transactional
    public AdminModelFallbackBatchMemberResponse batchSetMemberEnabled(
            Long id,
            AdminModelFallbackBatchEnabledRequest request) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        boolean enabled = request.enabled();
        int applied = 0;
        int unchanged = 0;
        List<AdminModelFallbackBatchMemberResponse.Skipped> skipped = new ArrayList<>();
        for (Long modelId : request.distinctModelIds()) {
            LlmModelFallbackMemberEntity member =
                    members.findByGroupIdAndModelId(id, modelId).orElse(null);
            if (member == null) {
                skipped.add(new AdminModelFallbackBatchMemberResponse.Skipped(
                        modelId, NOT_A_MEMBER_REASON));
                continue;
            }
            if (member.isEnabled() == enabled) {
                unchanged += 1;
                continue;
            }
            if (enabled) {
                String problem = memberProblem(group, requireModel(modelId));
                if (problem != null) {
                    skipped.add(new AdminModelFallbackBatchMemberResponse.Skipped(
                            modelId, problem));
                    continue;
                }
            }
            member.setEnabled(enabled);
            members.save(member);
            applied += 1;
        }
        members.flush();
        return new AdminModelFallbackBatchMemberResponse(
                detailOf(group), request.modelIds().size(), applied, unchanged, skipped);
    }

    /**
     * Removes many members in one transaction, then re-indexes priorities exactly once.
     *
     * <p>Re-indexing per deletion would issue one full renumber pass per row; all that matters is
     * that the surviving members end up contiguous, so it runs once after the deletes are flushed.
     * An id that is not a member is skipped, which makes retries idempotent.</p>
     */
    @Transactional
    public AdminModelFallbackBatchMemberResponse batchRemoveMembers(
            Long id,
            AdminModelFallbackBatchRemoveRequest request) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        int applied = 0;
        List<AdminModelFallbackBatchMemberResponse.Skipped> skipped = new ArrayList<>();
        for (Long modelId : request.distinctModelIds()) {
            LlmModelFallbackMemberEntity member =
                    members.findByGroupIdAndModelId(id, modelId).orElse(null);
            if (member == null) {
                skipped.add(new AdminModelFallbackBatchMemberResponse.Skipped(
                        modelId, NOT_A_MEMBER_REASON));
                continue;
            }
            members.delete(member);
            applied += 1;
        }
        if (applied > 0) {
            members.flush();
            reindexRemainingMembers(id);
            members.flush();
        }
        return new AdminModelFallbackBatchMemberResponse(
                detailOf(group), request.modelIds().size(), applied, 0, skipped);
    }

    @Transactional
    public AdminModelFallbackGroupResponse reorder(
            Long id,
            AdminModelFallbackOrderRequest request) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        List<LlmModelFallbackMemberEntity> current =
                members.findByGroupIdOrderByPriorityAscIdAsc(id);
        Map<Long, LlmModelFallbackMemberEntity> byModelId = current.stream()
                .collect(Collectors.toMap(
                        LlmModelFallbackMemberEntity::getModelId,
                        Function.identity()));
        List<Long> requestedIds = request.modelIds();
        Set<Long> uniqueIds = new HashSet<>(requestedIds);
        if (uniqueIds.size() != requestedIds.size()
                || !uniqueIds.equals(byModelId.keySet())) {
            throw new InvalidRequestException(
                    "modelIds must contain every current member exactly once");
        }

        for (int index = 0; index < requestedIds.size(); index += 1) {
            byModelId.get(requestedIds.get(index)).setPriority(index);
        }
        members.saveAll(current);
        group.setOrderCustomized(true);
        groups.save(group);
        return detailOf(group);
    }

    @Transactional
    public AdminModelFallbackGroupResponse usePriceOrder(Long id) {
        LlmModelFallbackGroupEntity group = requireGroup(id);
        List<LlmModelFallbackMemberEntity> current =
                members.findByGroupIdOrderByPriorityAscIdAsc(id);
        Map<Long, LlmModelEntity> modelsById = modelsById(current.stream()
                .map(LlmModelFallbackMemberEntity::getModelId)
                .toList());
        Map<String, ModelPriceView> prices = modelPrices.pricesByModelKey();
        List<LlmModelFallbackMemberEntity> priceOrder = new ArrayList<>(current);
        priceOrder.sort(Comparator
                .comparing(
                        (LlmModelFallbackMemberEntity member) ->
                                priceOf(prices, modelsById.get(member.getModelId())).sortPrice(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(member ->
                        modelsById.get(member.getModelId()).getSortOrder())
                .thenComparing(member ->
                        modelsById.get(member.getModelId()).getId()));
        for (int index = 0; index < priceOrder.size(); index += 1) {
            priceOrder.get(index).setPriority(index);
        }
        members.saveAll(priceOrder);
        group.setOrderCustomized(false);
        groups.save(group);
        return detailOf(group);
    }

    public List<AdminModelFallbackCandidateResponse> candidates(String modelType,
                                                                Long siteId) {
        String type = normalizeTypeFilter(modelType);
        if (siteId != null) {
            validateSite(siteId);
        }
        Map<String, ModelPriceView> prices = modelPrices.pricesByModelKey();
        // 分组以目录同步写入的 upstream_groups 为准，公开定价目录只作为兜底：
        // 定价目录只公开 default 这类少数分组，图片/视频模型经常整条查不到。
        Map<String, List<String>> plazaGroups = modelPrices.upstreamGroupsByModelKey();
        return models.findAll().stream()
                .filter(model -> type == null || type.equals(model.getModelType()))
                .filter(model -> LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus()))
                .filter(LlmModelEntity::isUpstreamAvailable)
                .filter(model -> model.getSiteId() == null
                        || Objects.equals(model.getSiteId(), siteId))
                .sorted(Comparator
                        .comparing(
                                (LlmModelEntity model) -> priceOf(prices, model).sortPrice(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(LlmModelEntity::getSortOrder)
                        .thenComparing(LlmModelEntity::getId))
                .map(model -> toCandidate(
                        model,
                        priceOf(prices, model),
                        candidateGroups(model, plazaGroups)))
                .toList();
    }

    private AdminModelFallbackGroupResponse detailOf(LlmModelFallbackGroupEntity group) {
        List<LlmModelFallbackMemberEntity> memberRows =
                members.findByGroupIdOrderByPriorityAscIdAsc(group.getId());
        Map<Long, LlmModelEntity> modelsById = modelsById(memberRows.stream()
                .map(LlmModelFallbackMemberEntity::getModelId)
                .toList());
        // 价格来自 New API 公开定价目录（带 5 分钟缓存），一次取齐再逐行填充。
        Map<String, ModelPriceView> prices = modelPrices.pricesByModelKey();
        List<AdminModelFallbackMemberResponse> memberResponses = memberRows.stream()
                .filter(member -> modelsById.containsKey(member.getModelId()))
                .map(member -> toMember(
                        group, member, modelsById.get(member.getModelId()), prices))
                .sorted(memberComparator(group, modelsById))
                .toList();
        int eligible = (int) memberResponses.stream()
                .filter(AdminModelFallbackMemberResponse::eligible)
                .count();
        return new AdminModelFallbackGroupResponse(
                group.getId(),
                group.getSiteId(),
                group.getSiteId() == null,
                group.getGroupKey(),
                group.getDisplayName(),
                group.getModelType(),
                group.getStatus(),
                group.getSortOrder(),
                group.isOrderCustomized(),
                group.isOrderCustomized() ? "CUSTOM" : "PRICE_ASC",
                group.getDurationMode(),
                parseDurations(group.getDurationsJson()),
                memberResponses.size(),
                eligible,
                memberResponses,
                group.getCreatedAt(),
                group.getUpdatedAt());
    }

    private AdminModelFallbackMemberResponse toMember(
            LlmModelFallbackGroupEntity group,
            LlmModelFallbackMemberEntity member,
            LlmModelEntity model,
            Map<String, ModelPriceView> prices) {
        String problem = memberProblem(group, model);
        ModelPriceView pricing = priceOf(prices, model);
        return new AdminModelFallbackMemberResponse(
                member.getId(),
                model.getId(),
                model.getModelKey(),
                model.getModelName(),
                model.getModelType(),
                model.getCompany(),
                model.resolvedRoute(),
                model.getSiteId(),
                model.getStatus(),
                model.isUpstreamAvailable(),
                pricing.sortPrice(),
                pricing,
                member.getPriority(),
                member.isEnabled(),
                problem == null && member.isEnabled(),
                member.isEnabled() ? problem : "member is disabled",
                member.getCreatedAt(),
                member.getUpdatedAt());
    }

    private static ModelPriceView priceOf(Map<String, ModelPriceView> prices, LlmModelEntity model) {
        if (model == null || model.getModelKey() == null) {
            return ModelPriceView.unpriced();
        }
        return prices.getOrDefault(model.getModelKey(), ModelPriceView.unpriced());
    }

    private Comparator<AdminModelFallbackMemberResponse> memberComparator(
            LlmModelFallbackGroupEntity group,
            Map<Long, LlmModelEntity> modelsById) {
        if (group.isOrderCustomized()) {
            return Comparator.comparingInt(AdminModelFallbackMemberResponse::priority)
                    .thenComparing(AdminModelFallbackMemberResponse::modelId);
        }
        return Comparator.comparing(
                        (AdminModelFallbackMemberResponse member) ->
                                member.pricing() == null ? null : member.pricing().sortPrice(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(member ->
                        modelsById.get(member.modelId()).getSortOrder())
                .thenComparing(AdminModelFallbackMemberResponse::modelId);
    }

    private void requireMemberCanJoin(LlmModelFallbackGroupEntity group, LlmModelEntity model) {
        String problem = memberProblem(group, model);
        if (problem != null) {
            throw new InvalidRequestException(
                    "Model '%s' cannot join fallback group '%s': %s"
                            .formatted(model.getModelKey(), group.getGroupKey(), problem));
        }
    }

    private String memberProblem(LlmModelFallbackGroupEntity group, LlmModelEntity model) {
        if (!LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus())) {
            return "model status is " + model.getStatus();
        }
        if (!model.isUpstreamAvailable()) {
            return "upstream is unavailable";
        }
        if (!group.getModelType().equalsIgnoreCase(model.getModelType())) {
            return "model type %s does not match group type %s"
                    .formatted(model.getModelType(), group.getModelType());
        }
        if (group.getSiteId() == null && model.getSiteId() != null) {
            return "a global group can only contain global models";
        }
        if (group.getSiteId() != null
                && model.getSiteId() != null
                && !Objects.equals(group.getSiteId(), model.getSiteId())) {
            return "a site group can only contain global models or models from the same site";
        }
        return null;
    }

    /**
     * Writes the group's duration override, or clears it when the request leaves it out.
     *
     * <p>Only VIDEO groups may carry one: the field exists to describe clip length, and a CHAT
     * group silently keeping a stale value would be a trap for the next operator. Both halves are
     * written together so a mode without values (or the reverse) can never reach the catalogue.</p>
     *
     * @throws InvalidRequestException when the override is set on a non-video group, the mode is
     *                                 unknown, or the values are missing / out of range
     */
    private void applyDurationOverride(LlmModelFallbackGroupEntity group,
                                       AdminModelFallbackGroupRequest request) {
        String rawMode = request.durationMode() == null ? null : request.durationMode().strip();
        List<Integer> rawDurations = request.durations();
        if ((rawMode == null || rawMode.isEmpty())
                && (rawDurations == null || rawDurations.isEmpty())) {
            group.setDurationMode(null);
            group.setDurationsJson(null);
            return;
        }
        if (!LlmModelEntity.TYPE_VIDEO.equals(group.getModelType())) {
            throw new InvalidRequestException(
                    "durationMode / durations can only be set on VIDEO groups");
        }
        if (rawMode == null || rawMode.isEmpty()) {
            throw new InvalidRequestException(
                    "durationMode is required when durations is provided");
        }
        String mode = rawMode.toLowerCase(Locale.ROOT);
        if (!"range".equals(mode) && !"set".equals(mode)) {
            throw new InvalidRequestException("durationMode must be range or set");
        }
        if (rawDurations == null || rawDurations.isEmpty()) {
            throw new InvalidRequestException(
                    "durations is required when durationMode is provided");
        }
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
        for (Integer value : rawDurations) {
            if (value == null || value < 1 || value > 60) {
                throw new InvalidRequestException("duration must be between 1 and 60 seconds");
            }
            unique.add(value);
        }
        List<Integer> sorted = unique.stream().sorted().toList();
        if ("range".equals(mode) && sorted.size() > 2) {
            throw new InvalidRequestException(
                    "a range only takes its two bounds, e.g. [4,15]");
        }
        try {
            group.setDurationsJson(
                    objectMapper.writeValueAsString(sorted));
        } catch (Exception exception) {
            throw new InvalidRequestException("durations is not a valid integer list");
        }
        group.setDurationMode(mode);
    }

    private void reindexRemainingMembers(Long groupId) {
        List<LlmModelFallbackMemberEntity> remaining =
                members.findByGroupIdOrderByPriorityAscIdAsc(groupId);
        for (int index = 0; index < remaining.size(); index += 1) {
            remaining.get(index).setPriority(index);
        }
        members.saveAll(remaining);
    }

    private Map<Long, LlmModelEntity> modelsById(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LlmModelEntity> byId = new HashMap<>();
        models.findAllById(modelIds).forEach(model -> byId.put(model.getId(), model));
        return byId;
    }

    /**
     * 候选卡片上显示的「上游分组」。
     *
     * <p>优先用目录同步写入的 {@code llm_models.upstream_groups}：那是按站点同步来的真实
     * {@code enable_groups}，图片/视频模型也有值。</p>
     *
     * <p>同一个对外 key 可能有多行：历史手工建的行（{@code source=LOCAL}）永远不会被目录同步
     * 写到分组，只有同步行才有。这种行借同 key 兄弟行的分组——否则界面会把一个实际跑在
     * vip 组的模型显示成「没有分组」，运营会以为它走的是默认分组。</p>
     *
     * <p>最后才回落到公开定价目录，它只公开 {@code default} 这类少数分组，覆盖很窄。</p>
     */
    private List<String> candidateGroups(
            LlmModelEntity model,
            Map<String, List<String>> plazaGroups) {
        List<String> synced = model.upstreamGroupList();
        if (!synced.isEmpty()) {
            return synced;
        }
        List<String> sibling = siblingGroups(model.getModelKey());
        if (!sibling.isEmpty()) {
            return sibling;
        }
        return plazaGroups.getOrDefault(model.getModelKey(), List.of());
    }

    /**
     * 同 key 其它行声明的分组，口径与 {@code ModelGroupRouter} 一致：先找上游可用的行，
     * 再退回任意一行。两处必须同口径，否则「界面显示的分组」和「请求实际走的分组」会对不上。
     */
    private List<String> siblingGroups(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return List.of();
        }
        List<LlmModelEntity> siblings = models.findByModelKeyOrderByIdAsc(modelKey);
        for (LlmModelEntity sibling : siblings) {
            if (!sibling.isUpstreamAvailable()) {
                continue;
            }
            List<String> groups = sibling.upstreamGroupList();
            if (!groups.isEmpty()) {
                return groups;
            }
        }
        for (LlmModelEntity sibling : siblings) {
            List<String> groups = sibling.upstreamGroupList();
            if (!groups.isEmpty()) {
                return groups;
            }
        }
        return List.of();
    }

    private AdminModelFallbackCandidateResponse toCandidate(
            LlmModelEntity model,
            ModelPriceView pricing,
            List<String> upstreamGroups) {
        return new AdminModelFallbackCandidateResponse(
                model.getId(),
                model.getModelKey(),
                model.getModelName(),
                model.getModelType(),
                model.getCompany(),
                model.resolvedRoute(),
                model.getSiteId(),
                model.getSiteId() == null,
                pricing.sortPrice(),
                pricing,
                upstreamGroups,
                model.isThinking(),
                model.isSupportsVision(),
                model.isSupportsTools());
    }

    private LlmModelFallbackGroupEntity requireGroup(Long id) {
        return groups.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Fallback group %d was not found".formatted(id)));
    }

    private LlmModelEntity requireModel(Long id) {
        return models.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Model %d was not found".formatted(id)));
    }

    private void validateSite(Long siteId) {
        if (siteId != null && !sites.existsById(siteId)) {
            throw new NotFoundException("Site %d was not found".formatted(siteId));
        }
    }

    private void requireUniqueGroupKey(Long siteId, String groupKey, Long excludedId) {
        groups.findAll().stream()
                .filter(group -> !Objects.equals(group.getId(), excludedId))
                .filter(group -> Objects.equals(group.getSiteId(), siteId))
                .filter(group -> groupKey.equalsIgnoreCase(group.getGroupKey()))
                .findFirst()
                .ifPresent(group -> {
                    throw new ConflictException(
                            "Fallback group key '%s' already exists in this scope"
                                    .formatted(groupKey));
                });
    }

    /** @return the stored override values, or an empty list when the group carries none */
    private List<Integer> parseDurations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json, objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, Integer.class));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static String normalizeGroupKey(String value) {        String key = requiredText(value, "groupKey").toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new InvalidRequestException(
                    "groupKey may contain letters, digits, dot, underscore and hyphen");
        }
        return key;
    }

    private static String normalizeType(String value) {
        String type = requiredText(value, "modelType").toUpperCase(Locale.ROOT);
        if (!Set.of(
                LlmModelEntity.TYPE_CHAT,
                LlmModelEntity.TYPE_IMAGE,
                LlmModelEntity.TYPE_VIDEO,
                LlmModelEntity.TYPE_AUDIO).contains(type)) {
            throw new InvalidRequestException(
                    "modelType must be CHAT, IMAGE, VIDEO or AUDIO");
        }
        return type;
    }

    private static String normalizeTypeFilter(String value) {
        return value == null || value.isBlank()
                ? null
                : normalizeType(value);
    }

    private static String normalizeStatus(String value, String defaultValue) {
        String status = value == null || value.isBlank()
                ? defaultValue
                : value.strip().toUpperCase(Locale.ROOT);
        if (!LlmModelEntity.STATUS_ACTIVE.equals(status)
                && !"DISABLED".equals(status)) {
            throw new InvalidRequestException("status must be ACTIVE or DISABLED");
        }
        return status;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " must not be blank");
        }
        return value.strip();
    }
}

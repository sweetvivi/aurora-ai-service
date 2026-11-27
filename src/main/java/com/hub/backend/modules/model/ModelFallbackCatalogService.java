package com.hub.backend.modules.model;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.price.ModelPriceService;
import com.hub.backend.modules.model.price.ModelPriceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads model-version groups and turns their members into an ordered, executable candidate list.
 *
 * <p>The public catalogue, runtime resolver and admin preview all use this service. In particular,
 * the meaning of "valid member" and the default price ordering have one implementation.</p>
 */
@Service
@Transactional(readOnly = true)
public class ModelFallbackCatalogService {

    private final LlmModelFallbackGroupRepository groups;
    private final LlmModelFallbackMemberRepository members;
    private final LlmModelRepository models;
    private final ModelPriceService modelPrices;

    public ModelFallbackCatalogService(LlmModelFallbackGroupRepository groups,
                                       LlmModelFallbackMemberRepository members,
                                       LlmModelRepository models,
                                       ModelPriceService modelPrices) {
        this.groups = groups;
        this.members = members;
        this.models = models;
        this.modelPrices = modelPrices;
    }

    /**
     * Resolves an explicitly requested group. An empty optional means no visible group carries the
     * key, in which case callers retain the legacy single-model resolution path.
     */
    public Optional<GroupView> findVisibleGroup(Long siteId, String groupKey, String requiredType) {
        LlmModelFallbackGroupEntity group = groups
                .findVisibleByGroupKeyForResolution(siteId, groupKey)
                .stream()
                .findFirst()
                .orElse(null);
        if (group == null) {
            return Optional.empty();
        }
        if (!LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(group.getStatus())) {
            throw new NotFoundException("Model '%s' is not available".formatted(groupKey));
        }
        if (requiredType != null && !requiredType.equalsIgnoreCase(group.getModelType())) {
            throw new InvalidRequestException(
                    "Model '%s' is registered as type %s and cannot be used for %s"
                            .formatted(groupKey, group.getModelType(), purpose(requiredType)));
        }

        GroupView view = view(group);
        if (view.candidates().isEmpty()) {
            throw new NotFoundException("Model '%s' is not available".formatted(groupKey));
        }
        return Optional.of(view);
    }

    /**
     * 「这个 key 能不能当默认值」的静默版：组不可用（停用、类型不符、成员为空）时返回 empty，
     * 而不是抛异常。
     *
     * <p><b>为什么必须独立事务。</b>调用方几乎都在自己的只读事务里做这个探测
     * （{@code ModelUseResolver} 是类级 {@code @Transactional(readOnly = true)}）。异常一旦
     * 穿过参与外层事务的代理边界，Spring 就会把<b>外层</b>标成 rollback-only；调用方 catch 住
     * 异常继续干活，最后却在外层 commit 时炸成
     * 「Transaction silently rolled back because it has been marked as rollback-only」。
     * 现场表现是：配置的默认模型是个空组 → 本该静默回退到可用模型 → 整次生图失败，
     * 而且日志里只有一句 rollback-only，看不出根因。REQUIRES_NEW 让异常只回滚这次探测。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<GroupView> findUsableGroupQuietly(Long siteId, String groupKey, String requiredType) {
        try {
            return findVisibleGroup(siteId, groupKey, requiredType);
        } catch (RuntimeException notUsableAsDefault) {
            return Optional.empty();
        }
    }

    /** Public groups, with same-key site groups replacing their global counterparts. */
    public List<GroupView> visibleGroups(Long siteId, String modelType) {
        String type = normalizeType(modelType);
        List<LlmModelFallbackGroupEntity> visible = type == null
                ? groups.findVisible(siteId, LlmModelEntity.STATUS_ACTIVE)
                : groups.findVisibleByType(siteId, type, LlmModelEntity.STATUS_ACTIVE);
        if (visible.isEmpty()) {
            return List.of();
        }

        Map<String, LlmModelFallbackGroupEntity> byKey = new LinkedHashMap<>();
        visible.stream()
                .sorted(Comparator.comparing(
                        group -> group.getSiteId() == null ? 0 : 1))
                .forEach(group -> byKey.put(group.getGroupKey(), group));
        return views(new ArrayList<>(byKey.values())).stream()
                .filter(view -> !view.candidates().isEmpty())
                .sorted(Comparator.comparing(
                                (GroupView view) -> lowestAnonymousPrice(view),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(
                                (GroupView view) -> view.group().getSortOrder())
                        .thenComparing(view -> view.group().getId()))
                .toList();
    }

    /** Finds the public group through which a configured concrete model is shown. */
    public Optional<GroupView> visibleGroupForModel(Long siteId, String modelType, Long modelId) {
        String type = normalizeType(modelType);
        if (type == null || modelId == null) {
            return Optional.empty();
        }
        return members.findByModelIdOrderByGroupIdAscPriorityAscIdAsc(modelId).stream()
                .map(member -> groups.findVisibleByGroupKeyForResolution(
                                siteId, requireGroup(member.getGroupId()).getGroupKey())
                        .stream()
                        .filter(group -> Objects.equals(group.getId(), member.getGroupId()))
                        .findFirst()
                        .orElse(null))
                .filter(Objects::nonNull)
                .map(this::view)
                .filter(view -> type.equalsIgnoreCase(view.group().getModelType()))
                .filter(view -> view.candidates().stream()
                        .anyMatch(candidate -> Objects.equals(candidate.model().getId(), modelId)))
                .findFirst();
    }

    public static BigDecimal effectivePrice(LlmModelEntity model) {
        BigDecimal consume = model.getConsumePoints();
        if (consume != null && consume.signum() > 0) {
            return consume;
        }
        BigDecimal input = model.getInputPointsPer1k();
        BigDecimal output = model.getOutputPointsPer1k();
        return (input == null ? BigDecimal.ZERO : input)
                .add(output == null ? BigDecimal.ZERO : output);
    }

    private List<GroupView> views(List<LlmModelFallbackGroupEntity> groupRows) {
        List<Long> groupIds = groupRows.stream().map(LlmModelFallbackGroupEntity::getId).toList();
        Map<Long, List<LlmModelFallbackMemberEntity>> membersByGroup =
                members.findByGroupIdInOrderByPriorityAscIdAsc(groupIds).stream()
                        .collect(Collectors.groupingBy(
                                LlmModelFallbackMemberEntity::getGroupId,
                                LinkedHashMap::new,
                                Collectors.toList()));
        List<Long> modelIds = membersByGroup.values().stream()
                .flatMap(List::stream)
                .map(LlmModelFallbackMemberEntity::getModelId)
                .distinct()
                .toList();
        Map<Long, LlmModelEntity> modelsById = models.findAllById(modelIds).stream()
                .collect(Collectors.toMap(LlmModelEntity::getId, Function.identity()));

        return groupRows.stream()
                .map(group -> new GroupView(
                        group,
                        orderedCandidates(
                                group,
                                membersByGroup.getOrDefault(group.getId(), List.of()),
                                modelsById)))
                .toList();
    }

    private GroupView view(LlmModelFallbackGroupEntity group) {
        List<LlmModelFallbackMemberEntity> rows =
                members.findByGroupIdOrderByPriorityAscIdAsc(group.getId());
        List<Long> modelIds = rows.stream()
                .map(LlmModelFallbackMemberEntity::getModelId)
                .distinct()
                .toList();
        Map<Long, LlmModelEntity> modelsById = models.findAllById(modelIds).stream()
                .collect(Collectors.toMap(LlmModelEntity::getId, Function.identity()));
        return new GroupView(group, orderedCandidates(group, rows, modelsById));
    }

    private List<Candidate> orderedCandidates(
            LlmModelFallbackGroupEntity group,
            List<LlmModelFallbackMemberEntity> rows,
            Map<Long, LlmModelEntity> modelsById) {
        Comparator<Candidate> comparator = group.isOrderCustomized()
                ? Comparator.comparingInt(Candidate::priority)
                        .thenComparing(candidate -> candidate.model().getId())
                : Comparator.comparing(
                                (Candidate candidate) -> anonymousPrice(candidate.model()),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(candidate -> candidate.model().getSortOrder())
                        .thenComparing(candidate -> candidate.model().getId());

        Map<String, Candidate> uniqueByRoute = new LinkedHashMap<>();
        rows.stream()
                .filter(LlmModelFallbackMemberEntity::isEnabled)
                .map(row -> new Candidate(row, modelsById.get(row.getModelId())))
                .filter(candidate -> candidate.model() != null)
                .filter(candidate -> validMember(group, candidate.model()))
                .sorted(comparator)
                .forEach(candidate -> uniqueByRoute.putIfAbsent(
                        normalizedRoute(candidate.model()), candidate));
        return List.copyOf(uniqueByRoute.values());
    }

    private BigDecimal anonymousPrice(LlmModelEntity model) {
        ModelPriceView price = modelPrices.priceOf(model.getModelKey());
        return price == null ? null : price.sortPrice();
    }

    private BigDecimal lowestAnonymousPrice(GroupView view) {
        return view.candidates().stream()
                .map(candidate -> anonymousPrice(candidate.model()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static boolean validMember(
            LlmModelFallbackGroupEntity group,
            LlmModelEntity model) {
        boolean visible = group.getSiteId() == null
                ? model.getSiteId() == null
                : model.getSiteId() == null || Objects.equals(model.getSiteId(), group.getSiteId());
        return visible
                && LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus())
                && model.isUpstreamAvailable()
                && group.getModelType().equalsIgnoreCase(model.getModelType());
    }

    private LlmModelFallbackGroupEntity requireGroup(Long id) {
        return groups.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Fallback group %d was not found".formatted(id)));
    }

    private static String normalizedRoute(LlmModelEntity model) {
        return model.resolvedRoute().strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank()
                ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String purpose(String requiredType) {
        return switch (requiredType.toUpperCase(Locale.ROOT)) {
            case LlmModelEntity.TYPE_CHAT -> "chat";
            case LlmModelEntity.TYPE_IMAGE -> "image generation";
            case LlmModelEntity.TYPE_VIDEO -> "video generation";
            default -> requiredType.toLowerCase(Locale.ROOT);
        };
    }

    public record Candidate(LlmModelFallbackMemberEntity member, LlmModelEntity model) {

        public int priority() {
            return member.getPriority();
        }
    }

    public record GroupView(
            LlmModelFallbackGroupEntity group,
            List<Candidate> candidates) {
    }
}

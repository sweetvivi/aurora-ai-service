package com.hub.backend.modules.model;

import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.NewApiGroupEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.NewApiGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一个模型该用哪个上游分组。
 *
 * <p>New API 的模型属于若干分组（{@code enable_groups}，同步进
 * {@code llm_models.upstream_groups}）；Relay Token 只能选其中一个分组。这里给出候选顺序，
 * 由凭据层去取对应分组的钥匙。</p>
 *
 * <p>顺序规则：先用配置的默认分组（{@code app.new-api.group}），因为那是历史行为，价格与渠道
 * 都是运营按它验过的；只有当模型不在默认分组里时，才按模型自己声明的顺序往下挑。反过来
 * （优先「第一个」分组）会让原本跑在默认分组上的请求被悄悄改道到别的分组，账单跟着变。</p>
 */
@Service
@Transactional(readOnly = true)
public class ModelGroupRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelGroupRouter.class);

    private final LlmModelRepository models;
    private final NewApiGroupRepository groups;

    public ModelGroupRouter(LlmModelRepository models, NewApiGroupRepository groups) {
        this.models = models;
        this.groups = groups;
    }

    /**
     * 该模型的候选分组，按优先级排列。
     *
     * @param modelId   目录行（{@code llm_models.id}）；{@code null} 表示未指定，返回空
     * @param preferred 优先使用的分组名（通常是 {@code app.new-api.group}），可为空
     * @return 候选分组；空表示「没有分组信息，按默认分组走」
     */
    public List<String> candidateGroups(Long modelId, String preferred) {
        if (modelId == null) {
            return List.of();
        }
        LlmModelEntity model = models.findById(modelId).orElse(null);
        if (model == null) {
            return List.of();
        }
        List<String> declared = model.upstreamGroupList();
        if (declared.isEmpty()) {
            // 同一个对外 key 可能有多行：历史手工建的行（source=LOCAL）永远不会被目录同步
            // 写到 upstream_groups，而同步行才有。目录里被选中的若是那条老行，分组信息就是空的，
            // 于是回落到账号令牌（default 组）——只在别的分组有渠道的模型会拿到
            // 「No available channel for model X under group default」，看起来像模型不可用。
            // 借同 key 其它行的分组，比直接用 default 去撞要准。
            declared = siblingGroups(model.getModelKey());
        }
        if (declared.isEmpty()) {
            return List.of();
        }

        Set<String> usable = usableGroups();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String preferredGroup = normalize(preferred);
        if (preferredGroup != null && declared.contains(preferredGroup)
                && isUsable(preferredGroup, usable)) {
            ordered.add(preferredGroup);
        }
        for (String group : declared) {
            if (isUsable(group, usable)) {
                ordered.add(group);
            }
        }
        if (ordered.isEmpty()) {
            // 目录里一个都不「可用」：可能是 newapi_groups 还没同步过。按模型自述的顺序照用，
            // 让上游去拒绝 —— 这比凭本地一份过期分组表把模型判死要好。
            log.debug("Model {} declares groups {} but none is in the synced catalogue; "
                    + "using the declared order", modelId, declared);
            ordered.addAll(declared);
        }
        return List.copyOf(ordered);
    }

    /** 首选分组；没有分组信息时返回空，调用方回落到 {@code app.new-api.group}。 */
    public String preferredGroup(Long modelId, String preferred) {
        List<String> candidates = candidateGroups(modelId, preferred);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 同 key 其它行声明的分组。
     *
     * <p>优先取「上游可用且在售」的那行，其次任意一行；都没有分组信息时返回空。</p>
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

    private Set<String> usableGroups() {
        List<NewApiGroupEntity> usable = groups.findByUsableTrueOrderByRatioAscGroupKeyAsc();
        if (usable.isEmpty()) {
            return Set.of();
        }
        List<String> names = new ArrayList<>(usable.size());
        for (NewApiGroupEntity group : usable) {
            names.add(group.getGroupKey());
        }
        return Set.copyOf(names);
    }

    /** 分组表还没同步时不拦：{@code usable} 为空表示「不知道」，不是「都不可用」。 */
    private static boolean isUsable(String group, Set<String> usable) {
        return usable.isEmpty() || usable.contains(group);
    }

    private static String normalize(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        String trimmed = group.strip();
        if ("auto".equals(trimmed.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return trimmed;
    }
}

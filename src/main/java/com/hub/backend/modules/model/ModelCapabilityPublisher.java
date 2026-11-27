package com.hub.backend.modules.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.model.dto.ModelCapabilities;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 决定「一个模型 key 对外到底声明什么能力」的唯一地方。
 *
 * <p>目录接口和视频提交校验必须用同一份答案。历史原因是：目录按版本组发布、提交校验只看具体
 * 成员，于是运营在组上把时长放宽到 4–15 之后，前台给出 7 秒，后端却按 fast-GT 的 10/15 拒掉 ——
 * 页面看起来能点，点了就 400。</p>
 *
 * <p>规则：</p>
 * <ol>
 *   <li>不是一个版本组：就是这一行自己的声明（区间型时长展开成逐秒）。</li>
 *   <li>是版本组：先取所有成员的**交集**（时长 / 分辨率 / 画幅），因为成员之间并不一致；</li>
 *   <li>组上有运营写的时长覆盖时，再用它压住时长这一维 —— 时长是唯一「运营比成员更懂业务」
 *       的维度，覆盖它没有风险；分辨率做不到就是做不到，覆盖了只会让上游 400。</li>
 * </ol>
 */
@Component
public class ModelCapabilityPublisher {

    private final ModelFallbackCatalogService fallbackCatalog;
    private final ObjectMapper objectMapper;

    public ModelCapabilityPublisher(ModelFallbackCatalogService fallbackCatalog,
                                    ObjectMapper objectMapper) {
        this.fallbackCatalog = fallbackCatalog;
        this.objectMapper = objectMapper;
    }

    /**
     * @param siteId       caller's site, used to find the site-specific group that shadows a global
     * @param key          the key the caller asked for; may be a group key or a concrete model key
     * @param requiredType the type the caller needs, or {@code null} to accept any
     * @return the declaration to publish, or {@code null} when {@code key} is neither a visible
     *         group nor something this component can describe (the caller then falls back to
     *         reading the model row itself)
     */
    public ModelCapabilities publishedFor(Long siteId, String key, String requiredType) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Optional<ModelFallbackCatalogService.GroupView> group =
                fallbackCatalog.findVisibleGroup(siteId, key.strip(), requiredType);
        return group.map(this::publishedFor).orElse(null);
    }

    /** @return the declaration the catalogue publishes for one resolved group */
    public ModelCapabilities publishedFor(ModelFallbackCatalogService.GroupView view) {
        List<LlmModelEntity> members = view.candidates().stream()
                .map(ModelFallbackCatalogService.Candidate::model)
                .toList();
        if (members.isEmpty()) {
            return null;
        }
        ModelCapabilities representative = capabilitiesOf(members.get(0));
        if (representative == null) {
            // 代表成员没有声明：组也就无从谈起，交给调用方按自己的兜底处理
            return null;
        }
        List<ModelCapabilities> others = members.stream()
                .skip(1)
                .map(this::capabilitiesOf)
                .toList();
        ModelCapabilities narrowed = representative.narrowedWith(others);
        ModelCapabilities overridden = applyDurationOverride(view.group(), narrowed);
        return overridden != null ? overridden : narrowed;
    }

    /** @return the model's own declaration, expanded for clients; {@code null} when absent */
    public ModelCapabilities capabilitiesOf(LlmModelEntity model) {
        ModelCapabilities declared = ModelCapabilities.parse(
                model.getCapabilitiesJson(), objectMapper);
        return declared == null ? null : declared.published();
    }

    /**
     * 把组上的时长覆盖套到一份声明上。
     *
     * @return the overridden declaration, or {@code null} when the group carries no usable override
     */
    private ModelCapabilities applyDurationOverride(LlmModelFallbackGroupEntity group,
                                                    ModelCapabilities base) {
        if (group == null || group.getDurationMode() == null
                || group.getDurationMode().isBlank()
                || group.getDurationsJson() == null || group.getDurationsJson().isBlank()) {
            return null;
        }
        List<Integer> values;
        try {
            values = objectMapper.readValue(
                    group.getDurationsJson(),
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, Integer.class));
        } catch (Exception exception) {
            // 读不出来只可能是手改过库；退回按成员计算，别让整个目录 500。
            return null;
        }
        List<Integer> options = values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull)
                .filter(second -> second >= 1 && second <= 60)
                .distinct()
                .sorted()
                .toList();
        if (options.isEmpty()) {
            return null;
        }
        // published() 会把区间展开成逐秒：库里存 [4,15] 是给人看的，客户端要的是 12 个档位。
        return base.withDurations(options, group.getDurationMode()).published();
    }

    /** @return a function form so callers can map a list without repeating the method reference */
    public Function<LlmModelEntity, ModelCapabilities> reader() {
        return this::capabilitiesOf;
    }
}

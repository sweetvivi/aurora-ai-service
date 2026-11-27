package com.hub.backend.modules.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.dto.ModelCapabilities;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single place where a requested model key becomes "what to call upstream".
 *
 * <p>Every execution path goes through here: the chat endpoints ask for a {@code CHAT} model, the
 * image endpoint asks for an {@code IMAGE} one. That is not a style preference. This table has
 * already carried a bug where two call sites read it under different rules — the catalogue filtered
 * by site while the chat path did not, so one site could resolve another site's row and inherit its
 * upstream route and its pricing. Reading the same table twice is what made that possible.</p>
 *
 * <p>Model <em>type</em> had exactly the same shape: the column existed and the catalogue filtered
 * on it, but nothing on the execution path did. A drawing model could therefore be used as a chat
 * model (and the reverse), which also meant an image row could be stamped into a conversation as if
 * it were the language model that produced the answer. Routing both callers through one method
 * makes that impossible to reintroduce on one side only.</p>
 */
@Service
@Transactional(readOnly = true)
public class ModelUseResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelUseResolver.class);

    private final LlmModelRepository models;
    private final ObjectMapper objectMapper;
    private final AppSiteSettingRepository settings;
    private final LlmProperties llmProperties;
    private final ModelFallbackCatalogService fallbackCatalog;

    /** 回退日志按「站点 + 配置值 + 实际值」去重，避免每个请求刷一行 WARN */
    private final Set<String> warnedFallbacks = ConcurrentHashMap.newKeySet();

    public ModelUseResolver(LlmModelRepository models,
                            ObjectMapper objectMapper,
                            AppSiteSettingRepository settings,
                            LlmProperties llmProperties) {
        this(models, objectMapper, settings, llmProperties, null);
    }

    @Autowired
    public ModelUseResolver(LlmModelRepository models,
                            ObjectMapper objectMapper,
                            AppSiteSettingRepository settings,
                            LlmProperties llmProperties,
                            ModelFallbackCatalogService fallbackCatalog) {
        this.models = models;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.llmProperties = llmProperties;
        this.fallbackCatalog = fallbackCatalog;
    }

    /**
     * Resolves a model key for one specific use.
     *
     * @param siteId       site of the caller; visibility is global rows plus that site's own
     * @param requestedKey key the caller asked for, may be {@code null} or blank
     * @param fallbackKey  key to use when the caller did not name one
     * @param requiredType the {@code model_type} this use is allowed to run, e.g.
     *                     {@link LlmModelEntity#TYPE_CHAT}; {@code null} accepts any type
     * @return the resolved entry
     * @throws InvalidRequestException when the key belongs to a model of another type
     * @throws NotFoundException       when the entry is missing/inactive, or when an unlisted key
     *                                 is rejected because {@code app.llm.allow-unlisted-models} is
     *                                 false
     */
    public Resolved resolve(Long siteId, String requestedKey, String fallbackKey, String requiredType) {
        return resolveAll(siteId, requestedKey, fallbackKey, requiredType).get(0);
    }

    /**
     * Resolves the complete fallback chain. A registered group contributes every valid member in
     * its configured order. A concrete model key keeps the legacy single-candidate behaviour, and
     * an unknown key is still forwarded upstream unchanged.
     */
    public List<Resolved> resolveAll(Long siteId, String requestedKey, String fallbackKey,
                                     String requiredType) {
        String key = requestedKey == null || requestedKey.isBlank() ? fallbackKey : requestedKey.strip();

        if (fallbackCatalog != null) {
            Optional<ModelFallbackCatalogService.GroupView> group =
                    fallbackCatalog.findVisibleGroup(siteId, key, requiredType);
            if (group.isPresent()) {
                return group.get().candidates().stream()
                        .map(candidate -> toResolved(
                                candidate.model(), group.get().group().getGroupKey()))
                        .toList();
            }
        }

        Resolved direct = firstResolvable(siteId, key, requiredType)
                .map(entity -> require(entity, key, requiredType))
                .orElseGet(() -> unlisted(siteId, key, requiredType));
        return List.of(direct);
    }

    /**
     * Picks the row a key resolves to when the same key exists more than once.
     *
     * <p>A key can legitimately own several rows: New API's catalogue sync creates one per public
     * model, while an operator may also have registered the same model by hand against a supplier
     * that declares its own download origin. Taking the smallest id blindly means the <em>older</em>
     * row always wins, so once the sync marks an older duplicate unavailable every request for that
     * key answers 404 while the catalogue keeps listing the usable row — the list and the lookup
     * disagree, which is the one thing this table's visibility rules exist to prevent.</p>
     *
     * <p>So a usable row wins (listed, active, and of the requested type when one is required), and
     * only when no row is usable does the first row get returned — it is then {@link #require}
     * which reports the precise reason (retired, hidden, or registered as another type) instead of
     * being hidden behind a generic "not available".</p>
     */
    private Optional<LlmModelEntity> firstResolvable(Long siteId, String key, String requiredType) {
        List<LlmModelEntity> rows = models.findVisibleByModelKeyForResolution(siteId, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return rows.stream()
                .filter(row -> isUsable(row, requiredType))
                .findFirst()
                .or(() -> Optional.of(rows.get(0)));
    }

    private static boolean isUsable(LlmModelEntity row, String requiredType) {
        if (!row.isUpstreamAvailable()
                || !LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(row.getStatus())) {
            return false;
        }
        return requiredType == null || requiredType.equalsIgnoreCase(row.getModelType());
    }

    /** Returns the public group key for a configured concrete model, when one is visible. */
    public Optional<String> publicKeyForModel(Long siteId, String modelType, Long modelId) {
        if (fallbackCatalog == null) {
            return Optional.empty();
        }
        return fallbackCatalog.visibleGroupForModel(siteId, modelType, modelId)
                .map(view -> view.group().getGroupKey());
    }

    /**
     * What to do with a key that has no visible catalogue row.
     *
     * <p>Three cases, and they must not share one answer:</p>
     * <ol>
     *   <li><b>The key is blank</b> — nobody named a model and no default is configured. Say that,
     *       in operator language. {@code Model 'null' is not available} sends whoever debugs it
     *       looking for a model that was never mentioned.</li>
     *   <li><b>The catalogue holds no entry of this type at all</b> — the catalogue is not
     *       trustworthy yet: first boot, a failed or not-yet-run sync, or the window between two
     *       syncs. Rejecting here takes the whole capability down, because <em>every</em> key is
     *       unlisted when the table is empty: image, video and chat all answer 404 while the
     *       gateway is perfectly usable. Forward the key, leave a WARN.</li>
     *   <li><b>The catalogue does carry this type, just not this key</b> — the catalogue is
     *       authoritative, so honour {@code app.llm.allow-unlisted-models}. It defaults to false,
     *       which is what stops a client from sending an upstream route name and bypassing the
     *       catalogue, its type check and its site visibility.</li>
     * </ol>
     */
    private Resolved unlisted(Long siteId, String key, String requiredType) {
        if (key == null || key.isBlank()) {
            throw new ServiceUnavailableException(
                    "%s未指定，且站点没有配置可用的默认模型".formatted(defaultNoun(requiredType)));
        }

        // "The catalogue knows this type" must include retired, hidden and other-site rows: a model
        // that was registered and later disabled is precisely the case that must still be refused,
        // and a row owned by another site must stay invisible here rather than turn into "unknown"
        // and get forwarded upstream. Only a catalogue that has never carried this type at all is
        // treated as not-yet-trustworthy.
        boolean catalogueKnowsThisType = requiredType == null
                ? !models.findVisible(siteId, LlmModelEntity.STATUS_ACTIVE).isEmpty()
                : models.countByTypeForResolution(requiredType) > 0;
        if (!catalogueKnowsThisType) {
            log.warn("模型目录里还没有任何 {} 模型，暂时放行未登记的 '{}'（目录可能尚未同步）",
                    purpose(requiredType), key);
            return unresolved(key);
        }

        if (!llmProperties.isAllowUnlistedModels()) {
            throw new NotFoundException("Model '%s' is not available".formatted(key));
        }
        // Escape hatch while a brand-new upstream model is tried before the catalogue is updated.
        return unresolved(key);
    }

    /**
     * Resolves a model for a use that may not carry a user-chosen key, falling back to the site's
     * <em>current</em> default instead of the static server property.
     *
     * <p>This exists because of a real outage: every caller used to pass
     * {@code llmProperties.getDefaultModel()} as the fallback. That property is baked into the
     * deployment (yml/env), so when operations refreshed the catalogue — retiring
     * {@code deepseek-v4.1-flash} in favour of {@code deepseek-v4-1-flash-260910} — every endpoint
     * that had no explicit model started answering {@code 404 Model 'deepseek-v4.1-flash' is not
     * available}, while {@code /app/config} was already reporting the correct replacement. The
     * commerce compose path broke exactly this way (2026-10-29) while agent chat survived, because
     * only agent chat had been switched to the adaptive lookup.</p>
     *
     * <p>Routing the fallback through {@link #defaultModelForSite(Long)} keeps the "which model
     * runs when nobody chose one" decision in one place, so a caller cannot reintroduce the stale
     * property by simply passing it as {@code fallbackKey}.</p>
     *
     * @param requestedKey key the caller asked for; when blank the site default is used
     * @param requiredType the model type this use is allowed to run
     */
    public Resolved resolveForSite(Long siteId, String requestedKey, String requiredType) {
        return resolveAllForSite(siteId, requestedKey, requiredType).get(0);
    }

    /**
     * Resolves the complete fallback chain for a use that may not carry a user-chosen key.
     *
     * <p>Both the requested key and the site's current default may name a version group, so this is
     * the runtime entry point for callers that support fallback. Keeping the default lookup here
     * prevents individual endpoints from drifting back to the static deployment property and
     * losing the configured candidate order.</p>
     */
    public List<Resolved> resolveAllForSite(Long siteId, String requestedKey, String requiredType) {
        if (requestedKey != null && !requestedKey.isBlank()) {
            return resolveAll(siteId, requestedKey, null, requiredType);
        }

        String fallback = defaultModelForSite(siteId, requiredType);
        try {
            return resolveAll(siteId, null, fallback, requiredType);
        } catch (NotFoundException unavailable) {
            // Say what to fix. The English "Model 'x' is not available" reaches the user verbatim
            // and reads like a client mistake, while the cause is an operational one.
            throw new NotFoundException(
                    "%s '%s' 已下架或未启用，请在后台模型目录中重新指定可用模型"
                            .formatted(defaultNoun(requiredType), fallback));
        }
    }

    /**
     * Resolves without ever throwing, for a caller that has a free or degraded path to fall back on.
     *
     * <p>论文模块需要这个语义：大纲生成失败要静默回落模板（免费功能不能因为上游或配置问题变成
     * 错误页），全文生成则要在开流前就把模型名定下来。用 {@link #resolveForSite} 的话，一个
     * 不可用的 key 会直接抛 404 / 503。</p>
     *
     * <p>关键是<b>回退值也走同一套解析</b>。以前论文模块自己写的是「catch 住异常，然后
     * return llmProperties.getDefaultModel()」—— 返回的是<b>站点 key</b>，而成功路径返回的是
     * <b>上游模型名</b>。同一个方法两种口径，只有在渠道恰好把两个名字都挂了的时候才看不出来。</p>
     *
     * @param requestedKey key the caller asked for; blank means "use the site default"
     * @return never {@code null}; when even the default is unusable, an unlisted result carrying
     *         the default key is returned so the caller can still attempt the call
     */
    public Resolved resolveOrDefault(Long siteId, String requestedKey, String requiredType) {
        try {
            return resolveForSite(siteId, requestedKey, requiredType);
        } catch (RuntimeException | Error exception) {
            if (exception instanceof Error) {
                throw (Error) exception;
            }
            if (requestedKey == null || requestedKey.isBlank()) {
                // 默认模型本身不可用：没有别的可退，把默认 key 原样交回，由调用方决定是否尝试。
                String fallback = llmProperties.getDefaultModel();
                log.warn("模型 '{}' 不可用且站点无其它默认模型，按原样尝试：{}", fallback,
                        exception.getMessage());
                return unresolved(fallback, requiredType);
            }
            log.warn("请求的模型 '{}' 不可用，改用站点默认模型：{}", requestedKey, exception.getMessage());
            return resolveOrDefault(siteId, null, requiredType);
        }
    }

    /**
     * The chat model a site actually runs when the caller names none.
     *
     * <p>Site setting first, server default second, then validated against the visible catalogue:     * a retired or renamed key falls back to the first available chat model instead of failing the
     * request. Runtime callers must use this rather than {@code LlmProperties#getDefaultModel()}.</p>
     */
    public String defaultModelForSite(Long siteId) {
        String preferred = settings.findBySiteIdOrderBySettingKeyAsc(siteId).stream()
                .filter(setting -> AppSiteSettingEntity.KEY_DEFAULT_MODEL.equals(
                        setting.getSettingKey()))
                .map(AppSiteSettingEntity::getSettingValue)
                .map(ModelUseResolver::blankToNull)
                .filter(value -> value != null)
                .findFirst()
                .orElse(llmProperties.getDefaultModel());
        return resolveDefaultModel(siteId, preferred);
    }

    /**
     * The default model a site runs for one use, with type-aware fallback.
     *
     * <p>Only {@code CHAT} has a site-level override today ({@code app_site_settings.default_model});
     * the image default still comes from deployment config. Both are validated here so an operator
     * retiring a key cannot leave an endpoint pointing at a row that no longer exists — the failure
     * mode this class exists for.</p>
     *
     * @param requiredType {@link LlmModelEntity#TYPE_CHAT}, {@code IMAGE}, … ; {@code null} means chat
     */
    public String defaultModelForSite(Long siteId, String requiredType) {
        String type = nullToChat(requiredType);
        if (LlmModelEntity.TYPE_IMAGE.equalsIgnoreCase(type)) {
            return defaultImageModelForSite(siteId);
        }
        if (!LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(type)) {
            // 视频/音频还没有站点级默认值（只有 app.llm.image-model 这一条旁路）。静默拿对话
            // 模型顶上只会换来一个 type 不匹配的 400，不如在这里说清"你得显式指定"。
            throw new UnsupportedOperationException(
                    "%s 类型暂无站点默认模型配置，调用方必须显式指定".formatted(type));
        }
        return defaultModelForSite(siteId);
    }

    /**
     * The image model a site actually draws with when the caller names none.
     *
     * <p>Same problem as the chat default: {@code app.llm.image-model} is baked into the
     * deployment, and replacing {@code gpt-image-2} upstream made every image endpoint answer
     * {@code Model 'gpt-image-2' is not available} until someone redeployed. Substituting here
     * fixes it without a config edit.</p>
     *
     * <p>Substitution prefers a model that shares the retired row's supplier, then its company,
     * before falling back to any usable image model. Drawing models are not interchangeable the
     * way chat models are — a Seedream key routed to an OpenAI-compatible endpoint answers
     * something opaque, or silently bills the wrong account — so the closest neighbour on the same
     * route is the only substitution worth trying automatically. Anything broader still resolves,
     * but it is logged so someone notices.</p>
     */
    public String defaultImageModelForSite(Long siteId) {
        String preferred = blankToNull(llmProperties.getImageModel());
        List<LlmModelEntity> images = models.findVisible(siteId, LlmModelEntity.STATUS_ACTIVE)
                .stream()
                .filter(model -> LlmModelEntity.TYPE_IMAGE.equalsIgnoreCase(model.getModelType()))
                .toList();

        // 目录为空（启动中或同步失败）：保留配置值。此时没有任何依据去猜一个替代品，
        // 猜错会把请求打到错误的上游，比原样透传更难排查。
        if (images.isEmpty()) {
            return preferred;
        }
        if (preferred == null) {
            String first = images.get(0).getModelKey();
            warnImageFallback(siteId, "(未配置)", first);
            return first;
        }
        // 组 key 本身也是合法的默认值：它代表的是一条回退链，不是某一个模型。
        // 少了这一条，把默认值配成组 key 会被下面的逻辑当成"已退役的模型"，
        // 白白替换成某个具体 key —— 于是 ImageModelFallbackService 拿到的候选只有一个，
        // 组里配了多少成员都不生效，且只影响「调用方没指定模型」的路径（抠图/超清/消除/重绘）。
        if (isVisibleGroupKey(siteId, preferred)) {
            return preferred;
        }
        if (images.stream().anyMatch(model -> preferred.equals(model.getModelKey()))) {
            return preferred;
        }

        // Prefer a usable row when the same key has duplicates: inferring the substitute's supplier
        // from a row the sync already retired would point the replacement at the wrong account.
        List<LlmModelEntity> retired = firstResolvable(siteId, preferred, LlmModelEntity.TYPE_IMAGE)
                .map(List::of)
                .orElseGet(List::of);
        Long providerId = retired.stream().map(LlmModelEntity::getProviderId)
                .filter(Objects::nonNull).findFirst().orElse(null);
        String company = retired.stream().map(LlmModelEntity::getCompany)
                .map(ModelUseResolver::blankToNull).filter(Objects::nonNull)
                .findFirst().orElse(null);

        String substitute = null;
        if (providerId != null) {
            substitute = images.stream()
                    .filter(model -> providerId.equals(model.getProviderId()))
                    .map(LlmModelEntity::getModelKey).findFirst().orElse(null);
        }
        if (substitute == null && company != null) {
            substitute = images.stream()
                    .filter(model -> company.equalsIgnoreCase(model.getCompany()))
                    .map(LlmModelEntity::getModelKey).findFirst().orElse(null);
        }
        if (substitute == null) {
            substitute = images.get(0).getModelKey();
        }
        warnImageFallback(siteId, preferred, substitute);
        return substitute;
    }

    private void warnImageFallback(Long siteId, String configured, String substitute) {
        String signature = siteId + "|IMAGE|" + configured + "|" + substitute;
        if (warnedFallbacks.add(signature)) {
            log.warn("站点 {} 的默认绘图模型 '{}' 在模型目录中不可用，已回退到 '{}'；"
                            + "请检查 LLM_IMAGE_MODEL 或模型目录中 IMAGE 模型的启用状态",
                    siteId, configured, substitute);
        }
    }

    /**
     * 这个 key 指向一个可见的图像回退组吗？
     *
     * <p>组的可用性判定由 {@code findVisibleGroup} 负责（组状态、类型、成员是否为空），
     * 它用抛异常表达"不存在但不能用"。这里只关心"能不能当默认值"，所以走
     * {@code findUsableGroupQuietly}：一个不成立的组 key 应当继续走原有的"退役模型替代"逻辑，
     * 而不是让启动期解析直接失败。</p>
     *
     * <p><b>不能自己 try/catch。</b>本类是类级 {@code @Transactional(readOnly = true)}，
     * 被吞掉的异常会先把当前事务标成 rollback-only —— 之后照常返回，却在 commit 时抛
     * 「Transaction silently rolled back」。现场就是：默认绘图模型指向一个空组，本该回退到
     * 可用模型，结果整次生图失败且日志只有一句 rollback-only。静默查询必须走独立事务。</p>
     */
    private boolean isVisibleGroupKey(Long siteId, String key) {
        if (fallbackCatalog == null) {
            return false;
        }
        return fallbackCatalog.findUsableGroupQuietly(siteId, key, LlmModelEntity.TYPE_IMAGE).isPresent();
    }

    private static String nullToChat(String requiredType) {
        return requiredType == null || requiredType.isBlank()
                ? LlmModelEntity.TYPE_CHAT : requiredType;
    }

    /**
     * Validates a candidate default against the visible catalogue and substitutes a usable key when
     * it was retired.
     *
     * <p>Exposed because {@code AppConfigService} has already parsed the settings rows by the time
     * it needs this; duplicating the validation there is what let the two copies drift before.
     * An empty catalogue keeps the configured value — during bootstrap there is nothing to compare
     * against, and a guessed key would be worse than the operator's intent.</p>
     */
    public String resolveDefaultModel(Long siteId, String preferred) {
        if (fallbackCatalog != null) {
            List<ModelFallbackCatalogService.GroupView> groups =
                    fallbackCatalog.visibleGroups(siteId, LlmModelEntity.TYPE_CHAT);
            if (!groups.isEmpty()) {
                boolean preferredGroup = preferred != null && groups.stream()
                        .anyMatch(group -> preferred.equals(group.group().getGroupKey()));
                if (preferredGroup) {
                    return preferred;
                }
                for (ModelFallbackCatalogService.GroupView group : groups) {
                    boolean containsPreferred = preferred != null && group.candidates().stream()
                            .anyMatch(candidate -> preferred.equals(candidate.model().getModelKey()));
                    if (containsPreferred) {
                        return group.group().getGroupKey();
                    }
                }
                return groups.get(0).group().getGroupKey();
            }
        }

        List<LlmModelEntity> visible = models.findVisible(siteId, LlmModelEntity.STATUS_ACTIVE);
        if (visible.isEmpty()) {
            return preferred;
        }

        boolean preferredAvailable = visible.stream().anyMatch(model ->
                preferred != null
                        && preferred.equals(model.getModelKey())
                        && LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType()));
        if (preferredAvailable) {
            return preferred;
        }

        String substitute = visible.stream()
                .filter(model -> LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType()))
                .map(LlmModelEntity::getModelKey)
                .findFirst()
                .orElse(null);
        if (substitute == null) {
            return preferred;
        }

        // 静默回退过一次就够糟了：上次配置漂移没人发现，就是因为回退没有任何输出。
        String signature = siteId + "|" + preferred + "|" + substitute;
        if (warnedFallbacks.add(signature)) {
            log.warn("站点 {} 的默认模型 '{}' 在模型目录中不可用，已回退到 '{}'；"
                            + "请检查 app_site_settings.default_model 或 LLM_DEFAULT_MODEL",
                    siteId, preferred, substitute);
        }
        return substitute;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Resolved require(LlmModelEntity entity, String key, String requiredType) {
        String type = entity.getModelType();

        if (!entity.isUpstreamAvailable()) {
            throw new NotFoundException("Model '%s' is not available".formatted(key));
        }
        if (requiredType != null && !requiredType.equalsIgnoreCase(type)) {
            // Deliberately not a 404. The key exists and is listed in the catalogue — it is simply
            // served by another endpoint. A 404 would send the caller looking for a missing model
            // instead of fixing the request, and the client already has the type from the catalogue.
            throw new InvalidRequestException(
                    "Model '%s' is registered as type %s and cannot be used for %s"
                            .formatted(key, type, purpose(requiredType)));
        }
        if (!LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(entity.getStatus())) {
            throw new NotFoundException("Model '%s' is not available".formatted(key));
        }

        return toResolved(entity, key);
    }

    private Resolved toResolved(LlmModelEntity entity, String requestKey) {
        String type = entity.getModelType();
        ModelCapabilities capabilities = ModelCapabilities.parse(entity.getCapabilitiesJson(), objectMapper);
        ChatThinkingDefaults.Spec thinking = ChatThinkingDefaults.resolve(
                entity.getModelKey(), entity.isThinking(), capabilities);
        String thinkingParameter = thinking == null ? null : sanitizeThinkingParameter(thinking.parameter());
        boolean supportsThinking = entity.isThinking() && thinkingParameter != null;
        return new Resolved(entity.getId(), requestKey, entity.resolvedRoute(), type, true,
                supportsThinking, thinkingParameter, entity.isSupportsWebSearch(),
                supportsThinking ? thinking.onValue() : null,
                supportsThinking ? thinking.offValue() : null);
    }

    private static Resolved unresolved(String key) {
        return unresolved(key, null);
    }

    private static Resolved unresolved(String key, String modelType) {
        return new Resolved(null, key, key, modelType, false, false, null, false, null, null);
    }

    private static String sanitizeThinkingParameter(String parameter) {
        if (parameter == null) {
            return null;
        }
        String value = parameter.strip();
        return value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") ? value : null;
    }

    /** Human wording of the required type, used to say what the caller tried to do. */
    private static String purpose(String requiredType) {
        return switch (requiredType.toUpperCase(Locale.ROOT)) {
            case LlmModelEntity.TYPE_CHAT -> "chat";
            case LlmModelEntity.TYPE_IMAGE -> "image generation";
            case LlmModelEntity.TYPE_VIDEO -> "video generation";
            default -> requiredType.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * What to call the default entry for one use, in the language the operator reads.
     *
     * <p>Kept separate from {@link #purpose} because that one answers "what did the caller try to
     * do" (English, for a 400 a client developer sees) while this one answers "which setting should
     * an operator fix" — the same type, two audiences.</p>
     */
    private static String defaultNoun(String requiredType) {
        return switch (nullToChat(requiredType).toUpperCase(Locale.ROOT)) {
            case LlmModelEntity.TYPE_IMAGE -> "默认绘图模型";
            case LlmModelEntity.TYPE_VIDEO -> "默认视频模型";
            default -> "默认模型";
        };
    }

    /**
     * @param id          catalogue id, {@code null} when the key is not published
     * @param requestKey  key the caller asked for, echoed back in the response
     * @param upstreamKey key actually sent to the gateway
     * @param modelType   registered type, {@code null} when the key is not published
     * @param listed      whether a visible catalogue row was found
     */
    /**
     * @param supportsWebSearch whether the upstream accepts built-in {@code type:web_search}
     */
    public record Resolved(Long id, String requestKey, String upstreamKey,
                           String modelType, boolean listed, boolean thinking,
                           String thinkingParameter, boolean supportsWebSearch,
                           String thinkingOnValue, String thinkingOffValue) {
    }
}

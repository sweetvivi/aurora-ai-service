package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.SiteDefaultModelResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Reads and writes {@code app_site_settings.default_model} — the chat model a site runs when a
 * request names none.
 *
 * <p><b>Why this needs a write path at all.</b> The value is already read by
 * {@link ModelUseResolver#defaultModelForSite(Long)}, but nothing in the console could change it,
 * so it only ever moved by editing the database. Since most agents do not pin a model, this one
 * string is in practice what answers every conversation — an operator has to be able to see and
 * set it.</p>
 *
 * <p><b>Why writes are validated.</b> Storing a key the catalogue does not know turns every
 * "model not specified" request into a 404, and the resolver's fallback then hides the mistake
 * behind a warning in the log. Rejecting at write time is the only place the operator still finds
 * out about it.</p>
 */
@Service
public class SiteDefaultModelService {

    private final AppSiteSettingRepository settings;
    private final LlmModelRepository models;
    private final ModelUseResolver modelResolver;

    public SiteDefaultModelService(AppSiteSettingRepository settings,
                                   LlmModelRepository models,
                                   ModelUseResolver modelResolver) {
        this.settings = settings;
        this.models = models;
        this.modelResolver = modelResolver;
    }

    @Transactional(readOnly = true)
    public SiteDefaultModelResponse load(AppSiteEntity site) {
        String configured = settings
                .findBySiteIdAndSettingKey(site.getId(), AppSiteSettingEntity.KEY_DEFAULT_MODEL)
                .map(AppSiteSettingEntity::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .orElse(null);

        // 解析链本身包含「配置值已退役 → 回退到第一个可用对话模型」，所以这里拿到的就是真实生效值。
        String effective = modelResolver.defaultModelForSite(site.getId());
        boolean fallbackUsed = configured != null && !Objects.equals(configured, effective);
        Boolean configuredValid = configured == null ? null : isUsableChatModel(site.getId(), configured);
        return new SiteDefaultModelResponse(site.getSiteKey(), configured, effective, fallbackUsed, configuredValid);
    }

    @Transactional
    public SiteDefaultModelResponse update(AppSiteEntity site, String modelKey) {
        String normalized = modelKey == null || modelKey.isBlank() ? null : modelKey.strip();
        var existing = settings.findBySiteIdAndSettingKey(site.getId(), AppSiteSettingEntity.KEY_DEFAULT_MODEL);

        if (normalized == null) {
            // 清空 = 回到部署默认（app.llm.model），不是「没有模型」。
            existing.ifPresent(settings::delete);
            return load(site);
        }

        if (!isUsableChatModel(site.getId(), normalized)) {
            throw new InvalidRequestException(
                    "模型 '%s' 不是当前站点可用的对话模型：需要在模型目录里存在、类型为 CHAT、状态为启用"
                            .formatted(normalized));
        }

        AppSiteSettingEntity setting = existing.orElseGet(() -> new AppSiteSettingEntity(
                site.getId(),
                AppSiteSettingEntity.KEY_DEFAULT_MODEL,
                null,
                AppSiteSettingEntity.TYPE_STRING));
        setting.setSettingValue(normalized);
        setting.setValueType(AppSiteSettingEntity.TYPE_STRING);
        settings.save(setting);
        return load(site);
    }

    /**
     * Whether this key still names an enabled chat model the site can see.
     *
     * <p>Deliberately stricter than the runtime resolver: the resolver has to keep working when the
     * catalogue lags behind upstream, but a write has an operator standing right there who can pick
     * something else.</p>
     */
    private boolean isUsableChatModel(Long siteId, String modelKey) {
        return models
                .findVisibleByModelKey(siteId, modelKey, LlmModelEntity.STATUS_ACTIVE)
                .stream()
                .anyMatch(model -> LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType()));
    }
}

package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.ModuleChatModelsResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ModuleChatModelService {

    public static final String SUPER_AGENT = "super-agent";
    public static final String AI_EDUCATION = "ai-education";
    public static final String COMMERCE = "commerce";
    public static final Set<String> MODULES = Set.of(SUPER_AGENT, AI_EDUCATION, COMMERCE);
    private static final String KEY_PREFIX = "model.chat.module.";

    private final AppSiteSettingRepository settings;
    private final LlmModelRepository models;
    private final ModelUseResolver resolver;

    public ModuleChatModelService(AppSiteSettingRepository settings, LlmModelRepository models,
                                  ModelUseResolver resolver) {
        this.settings = settings;
        this.models = models;
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public ModuleChatModelsResponse load(AppSiteEntity site) {
        Map<String, String> configured = new LinkedHashMap<>();
        settings.findBySiteIdOrderBySettingKeyAsc(site.getId()).stream()
                .filter(setting -> setting.getSettingKey().startsWith(KEY_PREFIX))
                .forEach(setting -> configured.put(
                        setting.getSettingKey().substring(KEY_PREFIX.length()), setting.getSettingValue()));
        return new ModuleChatModelsResponse(configured);
    }

    @Transactional
    public ModuleChatModelsResponse update(AppSiteEntity site, Map<String, String> requested) {
        if (requested == null || !MODULES.containsAll(requested.keySet())) {
            throw new InvalidRequestException("仅支持 super-agent、ai-education、commerce 模块");
        }
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String key = entry.getValue() == null || entry.getValue().isBlank()
                    ? null : entry.getValue().strip();
            var existing = settings.findBySiteIdAndSettingKey(site.getId(), settingKey(entry.getKey()));
            if (key == null) {
                existing.ifPresent(settings::delete);
                continue;
            }
            if (!isUsableChatModel(site.getId(), key)) {
                throw new InvalidRequestException("模型 '%s' 不是当前站点可用的 CHAT 模型".formatted(key));
            }
            AppSiteSettingEntity setting = existing.orElseGet(() -> new AppSiteSettingEntity(
                    site.getId(), settingKey(entry.getKey()), null, AppSiteSettingEntity.TYPE_STRING));
            setting.setSettingValue(key);
            settings.save(setting);
        }
        return load(site);
    }

    public List<ModelUseResolver.Resolved> resolveAllForSite(Long siteId, String module) {
        String configured = settings.findBySiteIdAndSettingKey(siteId, settingKey(module))
                .map(AppSiteSettingEntity::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
        return resolver.resolveAllForSite(siteId, configured, LlmModelEntity.TYPE_CHAT);
    }

    private boolean isUsableChatModel(Long siteId, String modelKey) {
        return models.findVisibleByModelKey(siteId, modelKey, LlmModelEntity.STATUS_ACTIVE)
                .stream().anyMatch(model -> LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType()));
    }

    private static String settingKey(String module) {
        return KEY_PREFIX + module;
    }
}

package com.hub.backend.modules.appconfig;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.modules.appconfig.dto.AppConfigResponse;
import com.hub.backend.modules.appconfig.dto.NavigationSection;
import com.hub.backend.modules.appconfig.dto.SiteBrandingLogos;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.ModelUseResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the initialisation payload of a site.
 *
 * <p>Layering: server defaults from {@link AppProperties} and {@link LlmProperties} first, then the
 * per-site rows of {@code app_site_settings}. A site with no rows at all therefore still answers
 * with a usable payload instead of failing.</p>
 */
@Service
@Transactional(readOnly = true)
public class AppConfigService {

    private final AppSiteSettingRepository settingRepository;
    private final AppProperties appProperties;
    private final LlmProperties llmProperties;
    private final NavigationSettingsLayoutCodec navigationLayoutCodec;
    private final ModelUseResolver modelResolver;
    private final SiteBrandingService siteBrandingService;

    public AppConfigService(AppSiteSettingRepository settingRepository,
                            AppProperties appProperties,
                            LlmProperties llmProperties,
                            NavigationSettingsLayoutCodec navigationLayoutCodec,
                            ModelUseResolver modelResolver,
                            SiteBrandingService siteBrandingService) {
        this.settingRepository = settingRepository;
        this.appProperties = appProperties;
        this.llmProperties = llmProperties;
        this.navigationLayoutCodec = navigationLayoutCodec;
        this.modelResolver = modelResolver;
        this.siteBrandingService = siteBrandingService;
    }

    public AppConfigResponse loadConfig(AppSiteEntity site) {
        Map<String, Boolean> features = new LinkedHashMap<>(appProperties.getDefaultFeatures());
        Map<String, Boolean> navigation = NavigationSettingsCatalog.defaultVisibility();
        List<NavigationSection> navigationSections =
                NavigationSettingsCatalog.defaultSections();
        String announcement = null;
        String defaultModel = llmProperties.getDefaultModel();

        for (AppSiteSettingEntity setting : settingRepository.findBySiteIdOrderBySettingKeyAsc(site.getId())) {
            String featureName = setting.featureName();
            if (featureName != null) {
                features.put(featureName, setting.booleanValue(features.getOrDefault(featureName, false)));
                continue;
            }
            String navigationKey = NavigationSettingsCatalog.navigationKeyOf(setting.getSettingKey());
            if (navigationKey != null) {
                navigation.put(
                        navigationKey,
                        setting.booleanValue(navigation.getOrDefault(navigationKey, true)));
                continue;
            }
            if (NavigationSettingsCatalog.KEY_NAVIGATION_LAYOUT.equals(setting.getSettingKey())) {
                navigationSections =
                        navigationLayoutCodec.readStored(setting.getSettingValue());
                continue;
            }
            if (AppSiteSettingEntity.KEY_ANNOUNCEMENT.equals(setting.getSettingKey())) {
                announcement = blankToNull(setting.getSettingValue());
                continue;
            }
            if (AppSiteSettingEntity.KEY_DEFAULT_MODEL.equals(setting.getSettingKey())) {
                String configured = blankToNull(setting.getSettingValue());
                if (configured != null) {
                    defaultModel = configured;
                }
            }
        }

        SiteBrandingLogos logos = siteBrandingService.readLogos(site.getId());

        return new AppConfigResponse(
                site.getSiteKey(),
                site.getDisplayName(),
                site.getDomain(),
                site.getStatus(),
                modelResolver.resolveDefaultModel(site.getId(), defaultModel),
                navigation,
                navigationSections,
                features,
                announcement,
                logos,
                Instant.now());
    }

    /**
     * Returns the effective chat model for a site after applying its database setting and current
     * catalogue availability.
     *
     * <p>Delegates to {@link ModelUseResolver}: the validation and the fallback live there now, so
     * this payload, the agent chat, commerce compose and the media helpers all answer the same
     * question the same way. Two copies of "which model do we run" is what produced the 2026-10-29
     * "Model 'deepseek-v4.1-flash' is not available" outage on the e-commerce path.</p>
     */
    public String defaultModelForSite(Long siteId) {
        return modelResolver.defaultModelForSite(siteId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

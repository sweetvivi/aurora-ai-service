package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.NavigationSection;
import com.hub.backend.modules.appconfig.dto.NavigationSettingsResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NavigationSettingsService {

    private final AppSiteSettingRepository settingRepository;
    private final NavigationSettingsLayoutCodec layoutCodec;

    public NavigationSettingsService(AppSiteSettingRepository settingRepository,
                                     NavigationSettingsLayoutCodec layoutCodec) {
        this.settingRepository = settingRepository;
        this.layoutCodec = layoutCodec;
    }

    @Transactional(readOnly = true)
    public NavigationSettingsResponse load(AppSiteEntity site) {
        Map<String, Boolean> navigation = NavigationSettingsCatalog.defaultVisibility();
        List<NavigationSection> navigationSections = NavigationSettingsCatalog.defaultSections();
        for (AppSiteSettingEntity setting : settingRepository.findBySiteIdOrderBySettingKeyAsc(site.getId())) {
            String navigationKey = NavigationSettingsCatalog.navigationKeyOf(setting.getSettingKey());
            if (navigationKey != null) {
                navigation.put(navigationKey, setting.booleanValue(navigation.get(navigationKey)));
            } else if (NavigationSettingsCatalog.KEY_NAVIGATION_LAYOUT.equals(setting.getSettingKey())) {
                navigationSections = layoutCodec.readStored(setting.getSettingValue());
            }
        }
        return new NavigationSettingsResponse(site.getSiteKey(), navigation, navigationSections);
    }

    @Transactional
    public NavigationSettingsResponse update(
            AppSiteEntity site,
            Map<String, Boolean> visibilityChanges,
            List<NavigationSection> navigationSections) {
        if (visibilityChanges == null && navigationSections == null) {
            throw new InvalidRequestException(
                    "navigation or navigationSections must be provided");
        }

        if (visibilityChanges != null) {
            validateVisibility(visibilityChanges);
            for (Map.Entry<String, Boolean> entry : visibilityChanges.entrySet()) {
                String settingKey = NavigationSettingsCatalog.settingKey(entry.getKey());
                AppSiteSettingEntity setting = settingRepository
                        .findBySiteIdAndSettingKey(site.getId(), settingKey)
                        .orElseGet(() -> new AppSiteSettingEntity(
                                site.getId(),
                                settingKey,
                                null,
                                AppSiteSettingEntity.TYPE_BOOLEAN));
                setting.setSettingValue(Boolean.toString(entry.getValue()));
                setting.setValueType(AppSiteSettingEntity.TYPE_BOOLEAN);
                settingRepository.save(setting);
            }
        }

        if (navigationSections != null) {
            String encodedLayout = layoutCodec.writeStored(navigationSections);
            AppSiteSettingEntity setting = settingRepository
                    .findBySiteIdAndSettingKey(
                            site.getId(),
                            NavigationSettingsCatalog.KEY_NAVIGATION_LAYOUT)
                    .orElseGet(() -> new AppSiteSettingEntity(
                            site.getId(),
                            NavigationSettingsCatalog.KEY_NAVIGATION_LAYOUT,
                            null,
                            AppSiteSettingEntity.TYPE_STRING));
            setting.setSettingValue(encodedLayout);
            setting.setValueType(AppSiteSettingEntity.TYPE_STRING);
            settingRepository.save(setting);
        }

        return load(site);
    }

    private static void validateVisibility(Map<String, Boolean> changes) {
        for (Map.Entry<String, Boolean> entry : changes.entrySet()) {
            if (!NavigationSettingsCatalog.isKnownNavigationKey(entry.getKey())) {
                throw new InvalidRequestException(
                        "Unknown navigation key: " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new InvalidRequestException(
                        "Visibility value is required for navigation key: " + entry.getKey());
            }
        }
    }
}

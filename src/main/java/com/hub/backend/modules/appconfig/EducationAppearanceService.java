package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.EducationAppearanceRequest;
import com.hub.backend.modules.appconfig.dto.EducationAppearanceResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EducationAppearanceService {

    private final AppSiteSettingRepository settingRepository;

    public EducationAppearanceService(AppSiteSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public EducationAppearanceResponse load(AppSiteEntity site) {
        String value = settingRepository
                .findBySiteIdAndSettingKey(site.getId(), AppSiteSettingEntity.KEY_AI_EDUCATION_HERO_IMAGE)
                .map(AppSiteSettingEntity::getSettingValue)
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .orElse(null);
        return new EducationAppearanceResponse(value);
    }

    @Transactional
    public EducationAppearanceResponse update(AppSiteEntity site, EducationAppearanceRequest request) {
        String value = normalizeUrl(request.heroImageUrl());
        AppSiteSettingEntity setting = settingRepository
                .findBySiteIdAndSettingKey(site.getId(), AppSiteSettingEntity.KEY_AI_EDUCATION_HERO_IMAGE)
                .orElseGet(() -> new AppSiteSettingEntity(
                        site.getId(),
                        AppSiteSettingEntity.KEY_AI_EDUCATION_HERO_IMAGE,
                        null,
                        AppSiteSettingEntity.TYPE_STRING));
        setting.setSettingValue(value);
        setting.setValueType(AppSiteSettingEntity.TYPE_STRING);
        settingRepository.save(setting);
        return new EducationAppearanceResponse(value);
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.startsWith("/media/") || value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        throw new InvalidRequestException("图片地址必须是 /media/ 相对地址或 HTTP(S) 地址");
    }
}

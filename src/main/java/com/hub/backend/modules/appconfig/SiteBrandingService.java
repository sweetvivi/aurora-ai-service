package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.SiteBrandingHistoryItem;
import com.hub.backend.modules.appconfig.dto.SiteBrandingHistoryResponse;
import com.hub.backend.modules.appconfig.dto.SiteBrandingLogos;
import com.hub.backend.modules.appconfig.dto.SiteBrandingRequest;
import com.hub.backend.modules.appconfig.dto.SiteBrandingResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.entity.BrandingLogoHistoryEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.appconfig.repository.BrandingLogoHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Site chrome logos stored in {@code app_site_settings}.
 *
 * <p>Slots are independent: clearing one only removes that override so the client can fall back
 * to {@code branding.logo.default}, then to its built-in asset. Actual URL changes are appended
 * to {@code branding_logo_history} for ops review.</p>
 */
@Service
public class SiteBrandingService {

    private static final int HISTORY_LIMIT = 50;

    /** setting_key → API slot name */
    private static final Map<String, String> KEY_TO_SLOT = Map.of(
            AppSiteSettingEntity.KEY_BRANDING_LOGO_DEFAULT, "default",
            AppSiteSettingEntity.KEY_BRANDING_LOGO_AUTH, "auth",
            AppSiteSettingEntity.KEY_BRANDING_LOGO_SIDENAV, "sidenav",
            AppSiteSettingEntity.KEY_BRANDING_LOGO_CHAT, "chat",
            AppSiteSettingEntity.KEY_BRANDING_LOGO_ASSISTANT, "assistant",
            AppSiteSettingEntity.KEY_BRANDING_LOGO_TOAST, "toast");

    private final AppSiteSettingRepository settingRepository;
    private final BrandingLogoHistoryRepository historyRepository;

    public SiteBrandingService(AppSiteSettingRepository settingRepository,
                               BrandingLogoHistoryRepository historyRepository) {
        this.settingRepository = settingRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public SiteBrandingResponse load(AppSiteEntity site) {
        return new SiteBrandingResponse(readLogos(site.getId()));
    }

    @Transactional(readOnly = true)
    public SiteBrandingLogos readLogos(Long siteId) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : KEY_TO_SLOT.keySet()) {
            settingRepository.findBySiteIdAndSettingKey(siteId, key)
                    .map(AppSiteSettingEntity::getSettingValue)
                    .map(SiteBrandingService::blankToNull)
                    .ifPresent(value -> values.put(key, value));
        }
        return new SiteBrandingLogos(
                values.get(AppSiteSettingEntity.KEY_BRANDING_LOGO_DEFAULT),
                values.get(AppSiteSettingEntity.KEY_BRANDING_LOGO_AUTH),
                values.get(AppSiteSettingEntity.KEY_BRANDING_LOGO_SIDENAV),
                values.get(AppSiteSettingEntity.KEY_BRANDING_LOGO_CHAT),
                values.get(AppSiteSettingEntity.KEY_BRANDING_LOGO_ASSISTANT),
                values.get(AppSiteSettingEntity.KEY_BRANDING_LOGO_TOAST));
    }

    @Transactional(readOnly = true)
    public SiteBrandingHistoryResponse history(AppSiteEntity site) {
        List<SiteBrandingHistoryItem> items = historyRepository
                .findBySiteIdOrderByCreatedAtDescIdDesc(site.getId(), PageRequest.of(0, HISTORY_LIMIT))
                .stream()
                .map(row -> new SiteBrandingHistoryItem(
                        row.getId(),
                        row.getSlotKey(),
                        row.getOldUrl(),
                        row.getNewUrl(),
                        row.getCreatedAt()))
                .toList();
        return new SiteBrandingHistoryResponse(items);
    }

    @Transactional
    public SiteBrandingResponse update(AppSiteEntity site, SiteBrandingRequest request) {
        upsert(site.getId(), AppSiteSettingEntity.KEY_BRANDING_LOGO_DEFAULT, request.defaultLogo());
        upsert(site.getId(), AppSiteSettingEntity.KEY_BRANDING_LOGO_AUTH, request.auth());
        upsert(site.getId(), AppSiteSettingEntity.KEY_BRANDING_LOGO_SIDENAV, request.sidenav());
        upsert(site.getId(), AppSiteSettingEntity.KEY_BRANDING_LOGO_CHAT, request.chat());
        upsert(site.getId(), AppSiteSettingEntity.KEY_BRANDING_LOGO_ASSISTANT, request.assistant());
        upsert(site.getId(), AppSiteSettingEntity.KEY_BRANDING_LOGO_TOAST, request.toast());
        return load(site);
    }

    private void upsert(Long siteId, String settingKey, String rawUrl) {
        String value = normalizeUrl(rawUrl);
        AppSiteSettingEntity setting = settingRepository
                .findBySiteIdAndSettingKey(siteId, settingKey)
                .orElseGet(() -> new AppSiteSettingEntity(
                        siteId, settingKey, null, AppSiteSettingEntity.TYPE_STRING));
        String previous = blankToNull(setting.getSettingValue());
        if (!Objects.equals(previous, value)) {
            historyRepository.save(new BrandingLogoHistoryEntity(
                    siteId, KEY_TO_SLOT.get(settingKey), previous, value));
        }
        setting.setSettingValue(value);
        setting.setValueType(AppSiteSettingEntity.TYPE_STRING);
        settingRepository.save(setting);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

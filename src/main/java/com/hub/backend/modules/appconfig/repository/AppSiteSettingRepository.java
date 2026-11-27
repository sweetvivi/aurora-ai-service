package com.hub.backend.modules.appconfig.repository;

import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppSiteSettingRepository extends JpaRepository<AppSiteSettingEntity, Long> {

    List<AppSiteSettingEntity> findBySiteIdOrderBySettingKeyAsc(Long siteId);

    Optional<AppSiteSettingEntity> findBySiteIdAndSettingKey(Long siteId, String settingKey);
}

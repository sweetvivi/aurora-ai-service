package com.hub.backend.modules.appconfig.repository;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppSiteRepository extends JpaRepository<AppSiteEntity, Long> {

    Optional<AppSiteEntity> findBySiteKey(String siteKey);

    Optional<AppSiteEntity> findFirstByDomainIgnoreCase(String domain);
}

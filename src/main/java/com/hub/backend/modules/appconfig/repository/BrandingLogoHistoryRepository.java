package com.hub.backend.modules.appconfig.repository;

import com.hub.backend.modules.appconfig.entity.BrandingLogoHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrandingLogoHistoryRepository extends JpaRepository<BrandingLogoHistoryEntity, Long> {

    List<BrandingLogoHistoryEntity> findBySiteIdOrderByCreatedAtDescIdDesc(Long siteId, Pageable pageable);
}

package com.hub.backend.modules.analytics.repository;

import com.hub.backend.modules.analytics.entity.SiteVisitEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteVisitRepository extends JpaRepository<SiteVisitEntity, Long> {

    long countBySiteId(Long siteId);
}

package com.hub.backend.modules.support.repository;

import com.hub.backend.modules.support.entity.SupportFaqEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportFaqRepository extends JpaRepository<SupportFaqEntity, Long> {

    List<SupportFaqEntity> findBySiteIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long siteId);

    List<SupportFaqEntity> findBySiteIdOrderBySortOrderAscIdAsc(Long siteId);
}

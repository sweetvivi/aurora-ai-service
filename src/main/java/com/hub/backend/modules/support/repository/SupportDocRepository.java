package com.hub.backend.modules.support.repository;

import com.hub.backend.modules.support.entity.SupportDocEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportDocRepository extends JpaRepository<SupportDocEntity, Long> {

    List<SupportDocEntity> findBySiteIdOrderByCreatedAtDescIdDesc(Long siteId);

    List<SupportDocEntity> findBySiteIdAndEnabledTrueOrderByCreatedAtDescIdDesc(Long siteId);
}

package com.hub.backend.modules.support.repository;

import com.hub.backend.modules.support.entity.SupportBotConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupportBotConfigRepository extends JpaRepository<SupportBotConfigEntity, Long> {

    Optional<SupportBotConfigEntity> findFirstBySiteId(Long siteId);

    Optional<SupportBotConfigEntity> findFirstBySiteIdIsNull();

    Optional<SupportBotConfigEntity> findFirstByOrderByIdAsc();
}

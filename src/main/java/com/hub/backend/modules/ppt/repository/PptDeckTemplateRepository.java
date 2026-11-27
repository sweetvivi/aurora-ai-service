package com.hub.backend.modules.ppt.repository;

import com.hub.backend.modules.ppt.entity.PptDeckTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PptDeckTemplateRepository extends JpaRepository<PptDeckTemplateEntity, Long> {

    Optional<PptDeckTemplateEntity> findByCode(String code);

    List<PptDeckTemplateEntity> findByStatusOrderBySortOrderAscIdAsc(int status);

    List<PptDeckTemplateEntity> findAllByOrderBySortOrderAscIdAsc();
}

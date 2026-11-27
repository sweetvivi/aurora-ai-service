package com.hub.backend.modules.ppt.repository;

import com.hub.backend.modules.ppt.entity.PptTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PptTemplateRepository extends JpaRepository<PptTemplateEntity, Long> {
    Optional<PptTemplateEntity> findByTemplateIdIgnoreCase(String templateId);
    List<PptTemplateEntity> findAllByOrderByUpdatedAtDesc();
    List<PptTemplateEntity> findAllByStatusOrderByUpdatedAtDesc(String status);
}

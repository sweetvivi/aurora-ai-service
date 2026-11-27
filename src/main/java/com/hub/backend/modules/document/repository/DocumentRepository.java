package com.hub.backend.modules.document.repository;

import com.hub.backend.modules.document.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    Optional<DocumentEntity> findByIdAndUserIdAndStatus(Long id, Long userId, String status);
}

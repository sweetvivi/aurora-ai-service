package com.hub.backend.modules.thesis.repository;

import com.hub.backend.modules.thesis.entity.ThesisDocumentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ThesisDocumentRepository extends JpaRepository<ThesisDocumentEntity, Long> {

    Optional<ThesisDocumentEntity> findByIdAndUserIdAndStatusNot(Long id, Long userId, String status);

    Page<ThesisDocumentEntity> findByUserIdAndStatusNotOrderByUpdatedAtDescIdDesc(
            Long userId, String status, Pageable pageable);

    Page<ThesisDocumentEntity> findByUserIdAndStatusNotAndTopicContainingIgnoreCaseOrderByUpdatedAtDescIdDesc(
            Long userId, String status, String keyword, Pageable pageable);

    long countByUserIdAndStatusNot(Long userId, String status);
}

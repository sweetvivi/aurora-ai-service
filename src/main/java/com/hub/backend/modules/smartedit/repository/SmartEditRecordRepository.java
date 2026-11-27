package com.hub.backend.modules.smartedit.repository;

import com.hub.backend.modules.smartedit.entity.SmartEditRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Record lookups are always owner scoped. A bare {@code findById} would expose another account's
 * canvas and history.
 */
public interface SmartEditRecordRepository extends JpaRepository<SmartEditRecordEntity, Long> {

    long countByStatus(String status);

    Page<SmartEditRecordEntity> findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long userId, String status, Pageable pageable);

    Optional<SmartEditRecordEntity> findByIdAndUserIdAndStatus(Long id, Long userId, String status);

    java.util.List<SmartEditRecordEntity> findByStatusOrderByCreatedAtDescIdDesc(String status);
}

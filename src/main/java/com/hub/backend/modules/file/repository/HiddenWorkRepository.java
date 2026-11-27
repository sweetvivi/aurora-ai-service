package com.hub.backend.modules.file.repository;

import com.hub.backend.modules.file.entity.HiddenWorkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HiddenWorkRepository extends JpaRepository<HiddenWorkEntity, Long> {

    Optional<HiddenWorkEntity> findByUserIdAndFileIdAndScope(Long userId, Long fileId, String scope);

    void deleteByUserIdAndFileIdAndScope(Long userId, Long fileId, String scope);

    /** Attachment deletion also clears the hide rows, so a recycled id cannot inherit them. */
    void deleteByFileId(Long fileId);
}

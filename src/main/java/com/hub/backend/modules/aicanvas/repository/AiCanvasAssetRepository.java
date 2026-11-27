package com.hub.backend.modules.aicanvas.repository;

import com.hub.backend.modules.aicanvas.entity.AiCanvasAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiCanvasAssetRepository extends JpaRepository<AiCanvasAssetEntity, String> {

    List<AiCanvasAssetEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId, String status);

    List<AiCanvasAssetEntity> findByUserIdAndCategoryAndStatusOrderByCreatedAtDesc(
            Long userId, String category, String status);

    Optional<AiCanvasAssetEntity> findByKeyAndUserIdAndStatus(
            String key, Long userId, String status);

    /** Existing material for this attachment, so linking the same file twice returns the first row. */
    Optional<AiCanvasAssetEntity> findFirstByUserIdAndSourceFileIdAndStatus(
            Long userId, Long sourceFileId, String status);

    /** All materials pointing at one attachment; they go away with it. */
    List<AiCanvasAssetEntity> findByUserIdAndSourceFileIdAndStatus(
            Long userId, Long sourceFileId, String status);

    /**
     * Same as above, batched.
     *
     * <p>Used by the delete-confirmation check: one query for the whole selection instead of
     * one per card, so a 30-image batch does not turn into 30 round trips.</p>
     */
    List<AiCanvasAssetEntity> findByUserIdAndSourceFileIdInAndStatus(
            Long userId, Collection<Long> sourceFileIds, String status);

    /**
     * Bytes this account actually stores in the canvas library.
     *
     * <p>Rows that only reference an attachment are excluded on purpose: their bytes are already
     * counted against the attachment quota, and counting them twice would defeat the point of
     * referencing them.</p>
     */
    @Query("""
            select coalesce(sum(asset.sizeBytes), 0)
            from AiCanvasAssetEntity asset
            where asset.userId = :userId and asset.status = :status and asset.sourceFileId is null
            """)
    long sumOwnedSizeByUserIdAndStatus(@Param("userId") Long userId, @Param("status") String status);
}

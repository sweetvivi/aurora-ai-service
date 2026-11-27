package com.hub.backend.modules.file.repository;

import com.hub.backend.modules.file.entity.UploadedFileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

/**
 * Finders are scoped by owner, so an attachment id from a client is never trusted on its own.
 */
public interface UploadedFileRepository extends JpaRepository<UploadedFileEntity, Long> {

    Optional<UploadedFileEntity> findByIdAndUserIdAndStatus(Long id, Long userId, String status);

    Page<UploadedFileEntity> findByUserIdAndStatusOrderByIdDesc(Long userId, String status, Pageable pageable);

    Page<UploadedFileEntity> findByUserIdAndConversationIdAndStatusOrderByIdDesc(
            Long userId, Long conversationId, String status, Pageable pageable);

    /**
     * One feature's outputs, newest first. {@code source} is the page that produced the file
     * ({@code painting}, {@code image_generation}, …), which is what makes a per-page work list
     * possible without joining conversations.
     */
    Page<UploadedFileEntity> findByUserIdAndSourceAndStatusOrderByIdDesc(
            Long userId, String source, String status, Pageable pageable);

    /**
     * Every picture this account produced, regardless of which page produced it.
     *
     * <p>V88 made works shared: the image-generation page, AI drawing, the smart-editing workbench
     * and the canvas material library read the same list, so {@code source} only stays as
     * provenance. Uploads (references, chat attachments, avatars) are excluded by asset type rather
     * than by source, because a user-supplied picture is never a work.</p>
     */
    @Query("""
            select f from UploadedFileEntity f
            where f.userId = :userId and f.status = :status
              and f.assetType in :assetTypes
              and f.mimeType like 'image/%'
            order by f.id desc
            """)
    Page<UploadedFileEntity> findWorks(
            @Param("userId") Long userId,
            @Param("assetTypes") Collection<String> assetTypes,
            @Param("status") String status,
            Pageable pageable);

    /**
     * Same list, minus the works the user removed from one page.
     *
     * <p>Removing a picture from the smart-editing workbench is a material action, not a deletion:
     * the row and the bytes stay, only that one list stops showing it.</p>
     */
    @Query("""
            select f from UploadedFileEntity f
            where f.userId = :userId and f.status = :status
              and f.assetType in :assetTypes
              and f.mimeType like 'image/%'
              and not exists (
                  select 1 from HiddenWorkEntity h
                  where h.fileId = f.id and h.userId = :userId and h.scope = :scope)
            order by f.id desc
            """)
    Page<UploadedFileEntity> findWorksExcludingHidden(
            @Param("userId") Long userId,
            @Param("assetTypes") Collection<String> assetTypes,
            @Param("status") String status,
            @Param("scope") String scope,
            Pageable pageable);

    /**
     * 当前占用的存储字节数（只算未被删除的行）。
     *
     * <p>走 Java 侧求和而不是逐条累加：附件行数会随出图线性增长，一条 SQL 比在内存里
     * 过一遍实体便宜得多。</p>
     */
    @Query("select coalesce(sum(f.sizeBytes), 0) from UploadedFileEntity f "
            + "where f.userId = :userId and f.status = :status")
    long sumSizeBytesByUserIdAndStatus(@Param("userId") Long userId, @Param("status") String status);
}

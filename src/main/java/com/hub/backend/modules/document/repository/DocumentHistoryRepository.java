package com.hub.backend.modules.document.repository;

import com.hub.backend.modules.document.dto.DocumentHistorySummary;
import com.hub.backend.modules.document.entity.DocumentHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentHistoryRepository
        extends JpaRepository<DocumentHistoryEntity, Long>,
        JpaSpecificationExecutor<DocumentHistoryEntity> {

    Optional<DocumentHistoryEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * History list with filters, projected straight to the summary DTO.
     *
     * <p>The filters use sentinel values instead of {@code :param is null}: a bare null bind on
     * ``:documentId is null`` makes Hibernate guess the parameter type, and the guess is not always
     * right on MySQL. Zero / empty string mean "no filter", which is also what the controller
     * normalises to.</p>
     *
     * <p>Only the summary columns are selected — the LONGTEXT body is never read on this path.</p>
     */
    @Query(value = """
            select new com.hub.backend.modules.document.dto.DocumentHistorySummary(
                h.id, h.documentId, h.documentName, h.documentFormat, h.action, h.label,
                h.status, h.errorMessage, h.fileFormat, h.truncated, h.resultChars,
                h.durationMs, h.createdAt)
              from DocumentHistoryEntity h
             where h.userId = :userId
               and (:documentId = 0 or h.documentId = :documentId)
               and (:action = '' or h.action = :action)
               and (:keyword = ''
                    or lower(h.documentName) like :keywordLike
                    or lower(h.label) like :keywordLike)
             order by h.createdAt desc, h.id desc
            """,
            countQuery = """
            select count(h)
              from DocumentHistoryEntity h
             where h.userId = :userId
               and (:documentId = 0 or h.documentId = :documentId)
               and (:action = '' or h.action = :action)
               and (:keyword = ''
                    or lower(h.documentName) like :keywordLike
                    or lower(h.label) like :keywordLike)
            """)
    Page<DocumentHistorySummary> search(@Param("userId") Long userId,
                                        @Param("documentId") long documentId,
                                        @Param("action") String action,
                                        @Param("keyword") String keyword,
                                        @Param("keywordLike") String keywordLike,
                                        Pageable pageable);

    /** Newest ids first; used by the retention trim, so it never loads the result bodies. */
    @Query("select h.id from DocumentHistoryEntity h where h.userId = :userId order by h.createdAt desc, h.id desc")
    List<Long> findIdsForUser(@Param("userId") Long userId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentHistoryEntity h where h.userId = :userId and h.id not in :keepIds")
    int deleteByUserIdAndIdNotIn(@Param("userId") Long userId, @Param("keepIds") List<Long> keepIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DocumentHistoryEntity h where h.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}

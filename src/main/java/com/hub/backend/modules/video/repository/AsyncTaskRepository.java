package com.hub.backend.modules.video.repository;

import com.hub.backend.modules.video.entity.AsyncTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Access to the shared job table, always scoped by owner.
 *
 * <p>Looking a task up by its public key alone would let one account read another account's
 * generation, so every finder carries {@code userId} — the same rule the conversation repository
 * documents.</p>
 */
public interface AsyncTaskRepository extends JpaRepository<AsyncTaskEntity, Long> {

    Optional<AsyncTaskEntity> findByTaskKeyAndTaskTypeAndUserId(
            String taskKey, String taskType, Long userId);

    Page<AsyncTaskEntity> findByUserIdAndTaskTypeOrderByCreatedAtDescIdDesc(
            Long userId, String taskType, Pageable pageable);

    long countByUserIdAndTaskTypeAndStatusIn(
            Long userId, String taskType, Collection<String> statuses);

    /**
     * Tasks the poller should look at: running jobs whose last update is older than the poll
     * interval, plus pending jobs that never got submitted (for example because the process
     * restarted between the insert and the submission).
     */
    @Query("""
            select t from AsyncTaskEntity t
            where t.taskType = :taskType
              and t.status in :statuses
              and t.updatedAt < :staleBefore
            order by t.id asc
            """)
    List<AsyncTaskEntity> findStale(
            @Param("taskType") String taskType,
            @Param("statuses") Collection<String> statuses,
            @Param("staleBefore") LocalDateTime staleBefore,
            Pageable pageable);

    /**
     * Atomically claims a queued row for upstream submission.
     *
     * <p>The video executor and the poller can both notice a {@code PENDING} task; only the first
     * claim may call the supplier. {@code updatedAt} is written explicitly because bulk JPQL
     * updates do not trigger {@code @UpdateTimestamp}.</p>
     *
     * @return 1 when this caller owns the submit, 0 when another worker already claimed it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AsyncTaskEntity t
               set t.status = 'RUNNING',
                   t.startedAt = coalesce(t.startedAt, :now),
                   t.updatedAt = :now
             where t.id = :id
               and t.status = 'PENDING'
            """)
    int claimPendingForSubmit(@Param("id") Long id, @Param("now") LocalDateTime now);
}

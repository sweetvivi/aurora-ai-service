package com.hub.backend.modules.gateway.repository;

import com.hub.backend.modules.gateway.entity.NewApiDeletionTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NewApiDeletionTaskRepository
        extends JpaRepository<NewApiDeletionTaskEntity, Long> {

    /**
     * 到点该处理的任务：PENDING 且已过 {@code next_attempt_at}，外加租约过期的 RUNNING
     * （上一个实例中途被杀，没机会回写状态，否则那行会永远挂着）。
     */
    @Query("""
            select t from NewApiDeletionTaskEntity t
            where (t.status = 'PENDING' and t.nextAttemptAt <= :now)
               or (t.status = 'RUNNING' and t.leaseUntil is not null and t.leaseUntil < :now)
            order by t.nextAttemptAt asc
            """)
    List<NewApiDeletionTaskEntity> findDue(@Param("now") LocalDateTime now, Pageable page);

    /** 幂等判断：同一个本地用户只排一次。 */
    Optional<NewApiDeletionTaskEntity> findFirstByLocalUserId(Long localUserId);

    /**
     * 认领一行。返回 0 说明别的实例已经拿走了（或这行已经不在待处理状态），本次跳过。
     *
     * <p>条件更新而不是先读后写：多实例同时扫到同一行时，只有一个能把状态从
     * {@code PENDING} 改成 {@code RUNNING}，剩下的拿到 0 行，不会两个实例同时去删同一个账号。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NewApiDeletionTaskEntity t
            set t.status = 'RUNNING',
                t.leaseOwner = :owner,
                t.leaseUntil = :leaseUntil,
                t.updatedAt = :now
            where t.id = :id
              and (t.status = 'PENDING' or (t.status = 'RUNNING' and t.leaseUntil < :now))
            """)
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);
}

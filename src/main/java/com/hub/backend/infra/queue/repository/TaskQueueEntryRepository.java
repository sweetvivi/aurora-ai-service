package com.hub.backend.infra.queue.repository;

import com.hub.backend.infra.queue.entity.TaskQueueEntryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 队列表的并发读写。
 *
 * <p>每一条状态推进都是「带条件的定向 UPDATE」：先查出候选，再逐行 CAS。查出来的瞬间别人可能
 * 已经领走了，所以真正的归属判定发生在 UPDATE 的 {@code where} 里，返回 1 才算拿到。</p>
 *
 * <p>{@code updatedAt} 一律显式写：bulk JPQL 不触发 {@code @UpdateTimestamp}。</p>
 */
public interface TaskQueueEntryRepository extends JpaRepository<TaskQueueEntryEntity, Long> {

    /** 可领取的候选：到点、且仍是 pending。 */
    @Query("""
            select e from TaskQueueEntryEntity e
             where e.queueName = :queueName
               and e.status = 'pending'
               and e.availableAt <= :now
             order by e.availableAt asc, e.id asc
            """)
    List<TaskQueueEntryEntity> findClaimable(@Param("queueName") String queueName,
                                             @Param("now") LocalDateTime now,
                                             Pageable pageable);

    /**
     * 抢占一行。
     *
     * @return 1 表示本次调用拿到；0 表示已被别人领走
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'claimed',
                   e.leaseOwner = :owner,
                   e.leaseUntil = :leaseUntil,
                   e.attempts = e.attempts + 1,
                   e.updatedAt = :now
             where e.id = :id
               and e.status = 'pending'
            """)
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'done',
                   e.leaseOwner = null,
                   e.leaseUntil = null,
                   e.lastError = null,
                   e.updatedAt = :now
             where e.id = :id
               and e.status = 'claimed'
               and e.leaseOwner = :owner
            """)
    int markDone(@Param("id") Long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'failed',
                   e.leaseOwner = null,
                   e.leaseUntil = null,
                   e.lastError = :error,
                   e.updatedAt = :now
             where e.id = :id
               and e.status = 'claimed'
               and e.leaseOwner = :owner
            """)
    int markFailed(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("error") String error,
                   @Param("now") LocalDateTime now);

    /** 可重试的失败：回到 pending，等到 {@code availableAt} 再被领取。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'pending',
                   e.leaseOwner = null,
                   e.leaseUntil = null,
                   e.availableAt = :availableAt,
                   e.lastError = :error,
                   e.updatedAt = :now
             where e.id = :id
               and e.status = 'claimed'
               and e.leaseOwner = :owner
            """)
    int requeue(@Param("id") Long id,
                @Param("owner") String owner,
                @Param("availableAt") LocalDateTime availableAt,
                @Param("error") String error,
                @Param("now") LocalDateTime now);

    /**
     * 主动归还：立即可领，并把领取时计的那一次尝试还回去。
     *
     * <p>归还的语义是「这条消息还没被真正执行」（线程池满了、消费者拒收），不该占重试额度；
     * 只按 {@code status} 与 {@code lease_owner} 判定，所以重复归还会命中 0 行。</p>
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'pending',
                   e.leaseOwner = null,
                   e.leaseUntil = null,
                   e.availableAt = :now,
                   e.attempts = case when e.attempts > 0 then e.attempts - 1 else 0 end,
                   e.updatedAt = :now
             where e.id = :id
               and e.status = 'claimed'
               and e.leaseOwner = :owner
            """)
    int release(@Param("id") Long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    /** 租约过期且已用完重试额度 → 判死。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'failed',
                   e.leaseOwner = null,
                   e.leaseUntil = null,
                   e.lastError = :error,
                   e.updatedAt = :now
             where e.status = 'claimed'
               and e.leaseUntil < :now
               and e.attempts >= e.maxAttempts
            """)
    int expireToFailed(@Param("error") String error, @Param("now") LocalDateTime now);

    /** 租约过期但还有额度 → 回到 pending。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TaskQueueEntryEntity e
               set e.status = 'pending',
                   e.leaseOwner = null,
                   e.leaseUntil = null,
                   e.availableAt = :now,
                   e.updatedAt = :now
             where e.status = 'claimed'
               and e.leaseUntil < :now
               and e.attempts < e.maxAttempts
            """)
    int expireToPending(@Param("now") LocalDateTime now);

    /**
     * 按队列与状态分组计数，供指标使用。
     *
     * <p>一次查全表分组，而不是「每个队列 × 每个状态」各查一次：队列数会随着消费方增加而增长，
     * 而指标刷新是周期性的后台动作，不该随队列数量线性膨胀查询数。</p>
     *
     * @return 每行 {@code [queueName(String), status(String), count(Long)]}
     */
    @Query("""
            select e.queueName, e.status, count(e)
              from TaskQueueEntryEntity e
             group by e.queueName, e.status
            """)
    List<Object[]> countGroupedByQueueAndStatus();

    /**
     * 每个队列最老的一条待办是何时创建的。
     *
     * <p>用 {@code createdAt} 而不是 {@code availableAt}：退避中的任务 availableAt 被推到未来，
     * 按它算出来的「年龄」反而变小，看起来像积压在缓解 —— 那正是最需要报警的时候。</p>
     *
     * @return 每行 {@code [queueName(String), oldestCreatedAt(LocalDateTime)]}
     */
    @Query("""
            select e.queueName, min(e.createdAt)
              from TaskQueueEntryEntity e
             where e.status = 'pending'
             group by e.queueName
            """)
    List<Object[]> oldestPendingCreatedAt();
}

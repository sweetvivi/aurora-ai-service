package com.hub.backend.modules.ppt.repository;

import com.hub.backend.modules.ppt.entity.PptJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PPT 任务的读写。
 *
 * <p>worker 推进状态的三条路（进度 / 成功 / 失败）都是**带条件的定向更新**，不是
 * 「findById → 改字段 → save 整行」。原因很具体：save 会把 worker 手里那份旧快照整行写回去，
 * 其中 {@code cancelled=false} 会把用户刚点的「取消」抹掉，任务于是继续烧模型调用。
 * 条件里带上 {@code cancelled} 与终态，取消一旦落地，后面的写入只会命中 0 行。</p>
 *
 * <p>{@code updatedAt} 必须显式写：bulk JPQL 不触发 {@code @UpdateTimestamp}（与
 * {@code AsyncTaskRepository#claimPendingForSubmit} 同一个理由）。</p>
 */
public interface PptJobRepository extends JpaRepository<PptJobEntity, String> {

    Optional<PptJobEntity> findByJobIdAndUserId(String jobId, Long userId);

    Page<PptJobEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<PptJobEntity> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status, Pageable pageable);

    /** 当前占用并发额度的任务数（排队 + 在跑）。准入判断与恢复扫描共用。 */
    long countByStatusIn(Collection<String> statuses);

    /**
     * 队列里「超时还没人领」的任务。
     *
     * <p>正常路径下 queued 只是建单到执行之间的瞬时状态，超过阈值还停在 queued 说明提交那一步
     * 丢了（进程在提交前退出、线程池拒绝后没重试）。扫描只按 {@code updatedAt} 判断，不再看租约：
     * 没被领取过的行本来就没有租约。</p>
     *
     * <p>{@code notBefore} 是复活的历史窗口：比它更早的任务交给
     * {@link #failAbandonedQueued} 判失败，避免修好这个 bug 的当天把几天前的任务全部跑一遍。</p>
     */
    @Query("""
            select j from PptJobEntity j
             where j.status = 'queued'
               and j.cancelled = false
               and j.updatedAt < :staleBefore
               and j.createdAt >= :notBefore
             order by j.createdAt asc
            """)
    List<PptJobEntity> findStaleQueued(@Param("staleBefore") LocalDateTime staleBefore,
                                       @Param("notBefore") LocalDateTime notBefore,
                                       Pageable pageable);

    /**
     * 把早已过期、还停在队列里的任务判失败。
     *
     * <p>这些行通常来自「提交丢失」那次故障。它们不该被复活，也不该永远占着额度：
     * 明确失败，让用户看到结果并自行重试。</p>
     *
     * @return 被判死的行数
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = 'failed',
                   j.message = '生成失败',
                   j.errorMessage = :errorMessage,
                   j.leaseOwner = null,
                   j.leaseUntil = null,
                   j.finishedAt = :now,
                   j.updatedAt = :now
             where j.status = 'queued'
               and j.cancelled = false
               and j.createdAt < :createdBefore
            """)
    int failAbandonedQueued(@Param("createdBefore") LocalDateTime createdBefore,
                            @Param("errorMessage") String errorMessage,
                            @Param("now") LocalDateTime now);

    /**
     * 原子领取：queued → parsing，并写入租约。
     *
     * <p>放在提交线程池之前（而不是 worker 里）执行，好处是「已领取」立刻落库：恢复扫描不会
     * 把还在线程池队列里排队的任务当成丢失再提交一遍，准入计数也立刻包含它。</p>
     *
     * @return 1 表示本次调用拿到任务；0 表示已被别的 worker 领走，或任务已被取消
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = 'parsing',
                   j.message = :message,
                   j.progressMsg = :message,
                   j.leaseOwner = :owner,
                   j.leaseUntil = :leaseUntil,
                   j.updatedAt = :now
             where j.jobId = :jobId
               and j.status = 'queued'
               and j.cancelled = false
            """)
    int claimForExecution(@Param("jobId") String jobId,
                          @Param("owner") String owner,
                          @Param("leaseUntil") LocalDateTime leaseUntil,
                          @Param("message") String message,
                          @Param("now") LocalDateTime now);

    /**
     * 归还领取：线程池拒收时把任务放回队列，等下一轮恢复扫描重试。
     *
     * <p>只在 {@code status='parsing' and lease_owner=:owner} 时生效，避免把已经推进到后面的
     * 任务拽回起点。</p>
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = 'queued',
                   j.message = :message,
                   j.progressMsg = :message,
                   j.leaseOwner = null,
                   j.leaseUntil = null,
                   j.updatedAt = :now
             where j.jobId = :jobId
               and j.status = 'parsing'
               and j.leaseOwner = :owner
            """)
    int releaseClaim(@Param("jobId") String jobId,
                     @Param("owner") String owner,
                     @Param("message") String message,
                     @Param("now") LocalDateTime now);

    /**
     * 判死：租约过期却仍停在「已开始」状态的任务。
     *
     * <p>这里选择判失败而不是重新排队 —— 崩溃可能发生在任意一次上游调用之后，重跑会再花一次
     * 模型费用；让用户明确看到失败并自行重试，比静默重扣一次便宜得多。</p>
     *
     * <p>本迁移之前创建的行没有租约（{@code leaseUntil} 为空），用 {@code updatedAt} 兜底，
     * 阈值与租约时长一致。</p>
     *
     * @return 被判死的行数
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = 'failed',
                   j.message = '生成失败',
                   j.errorMessage = :errorMessage,
                   j.leaseOwner = null,
                   j.leaseUntil = null,
                   j.finishedAt = :now,
                   j.updatedAt = :now
             where j.status in :statuses
               and j.cancelled = false
               and ((j.leaseUntil is not null and j.leaseUntil < :now)
                    or (j.leaseUntil is null and j.updatedAt < :legacyBefore))
            """)
    int failStuck(@Param("statuses") Collection<String> statuses,
                  @Param("errorMessage") String errorMessage,
                  @Param("now") LocalDateTime now,
                  @Param("legacyBefore") LocalDateTime legacyBefore);

    /**
     * 推进进度。只在「仍进行中且未取消」时生效。
     *
     * <p>同一条 UPDATE 顺带续期 {@code leaseUntil}：进度推进本身就证明执行者还活着，因此不需要
     * 额外的心跳任务。反过来说，租约过期就意味着「这段时间里一次进度都没写进来」，可以判死。</p>
     *
     * @return 1 表示写入成功；0 表示任务已被取消或已到终态
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = :status,
                   j.progress = :progress,
                   j.message = :message,
                   j.progressMsg = :message,
                   j.leaseUntil = :leaseUntil,
                   j.updatedAt = :now
             where j.jobId = :jobId
               and j.cancelled = false
               and j.status not in ('succeeded', 'failed', 'cancelled')
            """)
    int applyProgress(@Param("jobId") String jobId,
                      @Param("status") String status,
                      @Param("progress") int progress,
                      @Param("message") String message,
                      @Param("leaseUntil") LocalDateTime leaseUntil,
                      @Param("now") LocalDateTime now);

    /**
     * 收尾为成功。状态机写进 where：只有还在跑的行走得到这里。
     *
     * @return 1 表示这份产物有了主人；0 表示任务已被取消，调用方必须把刚存下的产物删掉
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = 'succeeded',
                   j.progress = 100,
                   j.message = '生成完成',
                   j.storageKey = :storageKey,
                   j.fileName = :fileName,
                   j.pageCount = :pageCount,
                   j.pointsCost = :pointsCost,
                   j.presentonPresentationId = :presentationId,
                   j.presentonPath = :enginePath,
                   j.errorMessage = null,
                   j.leaseOwner = null,
                   j.leaseUntil = null,
                   j.finishedAt = :now,
                   j.updatedAt = :now
             where j.jobId = :jobId
               and j.cancelled = false
               and j.status not in ('succeeded', 'failed', 'cancelled')
            """)
    int markSucceededIfActive(@Param("jobId") String jobId,
                              @Param("storageKey") String storageKey,
                              @Param("fileName") String fileName,
                              @Param("pageCount") Integer pageCount,
                              @Param("pointsCost") int pointsCost,
                              @Param("presentationId") String presentationId,
                              @Param("enginePath") String enginePath,
                              @Param("now") LocalDateTime now);

    /**
     * 收尾为失败。同样带条件：已取消 / 已终态的行不再被 worker 改写。
     *
     * @return 1 表示这次失败被记录；0 表示行已经不归 worker 管
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PptJobEntity j
               set j.status = 'failed',
                   j.message = '生成失败',
                   j.errorMessage = :errorMessage,
                   j.leaseOwner = null,
                   j.leaseUntil = null,
                   j.finishedAt = :now,
                   j.updatedAt = :now
             where j.jobId = :jobId
               and j.cancelled = false
               and j.status not in ('succeeded', 'failed', 'cancelled')
            """)
    int markFailedIfActive(@Param("jobId") String jobId,
                           @Param("errorMessage") String errorMessage,
                           @Param("now") LocalDateTime now);
}

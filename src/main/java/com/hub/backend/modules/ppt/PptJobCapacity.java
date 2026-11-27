package com.hub.backend.modules.ppt;

import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.modules.ppt.entity.PptJobEntity;
import com.hub.backend.modules.ppt.repository.PptJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * PPT 并发额度：以数据库里「排队 + 在跑」的行数为准。
 *
 * <p>原来这里是一个进程内 {@code Semaphore}，它有三个问题：重启后计数归零（旧任务还在库里跑，
 * 却当成没有）、跟恢复扫描没有共同语言——扫描把任务重新捡起来时那个信号量并不知道；现在换成
 * 计数查询，额度天然跟随真实数据存活。</p>
 *
 * <p><b>为什么还要一把进程内的锁</b>：光靠「先数再插」在单进程内也会超卖 —— 事务 A 插入但还没
 * 提交，事务 B 的 count 看不见它，两边都以为还剩一格。所以计数与插入必须在同一段临界区里，并且
 * <b>锁要一直持有到事务结束</b>（{@link #withAdmission} 把解锁挂在 {@code afterCompletion} 上）。
 * 只锁到方法返回是没用的：那时行还没提交，下一个线程照样数不到。</p>
 *
 * <p><b>已知边界</b>：这把锁只在单个 JVM 内有效。多副本时额度仍是「近似」的 —— 真正跑起来多少
 * 由执行器线程池与 {@code claimForExecution} 的 CAS 决定，这个额度只负责早一点回 429。要精确到
 * 跨实例，得换成 Redis 互斥或库里的行锁，见 {@code docs/scheduled-tasks.md} 的副本清单。</p>
 */
@Component
public class PptJobCapacity {

    private static final Logger log = LoggerFactory.getLogger(PptJobCapacity.class);

    private final PptJobRepository jobs;
    private final int capacity;
    /**
     * 公平锁：建单是低频操作（秒级一个），公平带来的吞吐损失可以忽略，换来的是不会被
     * 一个热点线程连续插队、把别人饿到超时。
     */
    private final ReentrantLock admission = new ReentrantLock(true);

    public PptJobCapacity(PptJobRepository jobs, PptProperties properties) {
        PptProperties.PptMaster cfg = properties.getPptMaster();
        this.jobs = jobs;
        this.capacity = Math.max(1, cfg.getJobConcurrency()) + Math.max(0, cfg.getJobQueueSize());
    }

    public boolean hasRoom() {
        return jobs.countByStatusIn(PptJobEntity.IN_FLIGHT_STATUSES) < capacity;
    }

    /**
     * 在准入锁内执行「检查额度 + 落库」，锁一直持有到事务提交/回滚。
     *
     * <p>调用方必须是事务方法：没有事务时锁会在方法返回时释放，退化成原来那个有窗口的检查
     * （这里会打一条 debug，方便排查为什么额度又超了）。</p>
     *
     * @throws TooManyRequestsException 额度已满
     */
    public <T> T withAdmission(Supplier<T> work) {
        admission.lock();
        boolean releasedToTransaction = false;
        try {
            if (!hasRoom()) {
                throw new TooManyRequestsException("PPT 生成队列已满，请稍后重试", 30);
            }
            T result = work.get();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        admission.unlock();
                    }
                });
                releasedToTransaction = true;
            } else {
                log.debug("PPT admission released before commit: no active transaction");
            }
            return result;
        } finally {
            if (!releasedToTransaction) {
                admission.unlock();
            }
        }
    }
}

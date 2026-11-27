package com.hub.backend.modules.ppt;

import com.hub.backend.common.config.properties.PptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把「没人管」的 PPT 任务捡回来。
 *
 * <p>建单只负责往库里写一行并把任务交给线程池，两步之间没有持久化的接力：进程在提交前退出、
 * 线程池拒收后没人重试，那一行就永远停在 {@code queued}，前端一直转圈、也永远不会失败。
 * 视频链路早就用 {@code async_tasks} + 定时扫描解决过同一个问题，这里补齐。</p>
 *
 * <p>每轮做三件事，顺序不能反：</p>
 * <ol>
 *   <li><b>判死中断</b>：租约过期却仍停在「已开始」状态的行 —— 执行者已经不在了，改成失败，
 *       让用户看到明确结果，而不是等一个永远不会到来的进度。</li>
 *   <li><b>判死过期</b>：在队列里躺了太久的历史行 —— 那是「提交丢失」留下的残骸，不该被复活。</li>
 *   <li><b>重投</b>：剩下的、确实只是丢了提交的任务 —— 重新领取并提交。</li>
 * </ol>
 *
 * <p>先判死再重投，是为了让本轮腾出的额度立刻被用到；反过来的话，被死任务占满的额度会让
 * 重投这一轮白跑。</p>
 *
 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：一轮扫完再等下一轮，数据库慢的时候不会堆积。</p>
 */
@Component
public class PptJobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PptJobRecoveryScheduler.class);

    private final PptJobService jobService;
    private final PptProperties properties;

    public PptJobRecoveryScheduler(PptJobService jobService, PptProperties properties) {
        this.jobService = jobService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.ppt.ppt-master.recovery-interval:30s}",
            initialDelayString = "${app.ppt.ppt-master.recovery-initial-delay:15s}")
    public void recover() {
        if (!properties.getPptMaster().isRecoveryEnabled()) {
            return;
        }
        try {
            int reaped = jobService.failInterruptedJobs();
            if (reaped > 0) {
                log.warn("Marked {} interrupted PPT job(s) as failed (lease expired)", reaped);
            }
            int abandoned = jobService.failAbandonedQueuedJobs();
            if (abandoned > 0) {
                log.warn("Marked {} abandoned PPT job(s) as failed (queued too long)", abandoned);
            }
            int resumed = jobService.resumeStaleQueuedJobs();
            if (resumed > 0) {
                log.info("Re-submitted {} stranded PPT job(s)", resumed);
            }
        } catch (RuntimeException exception) {
            // 一轮失败不能拖垮调度线程；下一轮重试即可。
            log.warn("PPT recovery sweep failed: {}", exception.getMessage());
        }
    }
}

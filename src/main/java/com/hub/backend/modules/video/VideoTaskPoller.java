package com.hub.backend.modules.video;

import com.hub.backend.common.config.properties.VideoProperties;
import com.hub.backend.modules.video.entity.AsyncTaskEntity;
import com.hub.backend.modules.video.repository.AsyncTaskRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives in-flight video jobs forward.
 *
 * <p>Polling lives in a scheduled sweep rather than in the submitting thread: a generation takes
 * minutes, and a sleeping thread per job would neither survive a restart nor scale past a handful of
 * accounts. Every cycle looks at tasks whose state is stale and asks the supplier for their state;
 * the task itself owns the timeout, so a lost upstream job cannot keep a row alive forever.</p>
 */
@Component
public class VideoTaskPoller {

    private static final Logger log = LoggerFactory.getLogger(VideoTaskPoller.class);

    private final AsyncTaskRepository tasks;
    private final VideoGenerationService videoService;
    private final VideoProperties properties;

    public VideoTaskPoller(AsyncTaskRepository tasks,
                           VideoGenerationService videoService,
                           VideoProperties properties) {
        this.tasks = tasks;
        this.videoService = videoService;
        this.properties = properties;
    }

    /**
     * One sweep. {@code fixedDelay} rather than {@code fixedRate}: when the upstream is slow the
     * next cycle should start after this one finished, not pile up behind it.
     *
     * <p>加了 {@code @SchedulerLock}：多副本时只有一个实例扫。提交本身有
     * {@code claimPendingForSubmit} 的 CAS 保护，但**状态查询没有** —— 两个实例各轮一遍，
     * 上游调用量直接翻倍，还会因为先后写入把进度改乱。</p>
     *
     * <p>{@code lockAtMostFor} 要大于一轮扫描的最坏耗时（批次 50，单个任务可能卡到分钟级），
     * 否则锁提前释放，第二个实例会插进来。真超过了也只是多一遍重复查询，不会重复提交。</p>
     */
    @SchedulerLock(name = "video-task-poll", lockAtMostFor = "${app.video.poll-lock-at-most:15m}")
    @Scheduled(fixedDelayString = "${app.video.poll-interval:5000}")
    public void pollRunningTasks() {
        List<AsyncTaskEntity> stale = tasks.findStale(
                AsyncTaskEntity.TYPE_VIDEO_GENERATION,
                List.of(AsyncTaskEntity.STATUS_PENDING, AsyncTaskEntity.STATUS_RUNNING),
                videoService.staleBefore(),
                PageRequest.of(0, properties.getPollBatchSize()));

        if (stale.isEmpty()) {
            return;
        }

        log.debug("Polling {} video task(s)", stale.size());
        for (AsyncTaskEntity task : stale) {
            try {
                videoService.poll(task.getId());
            } catch (RuntimeException exception) {
                // One broken task must not stop the sweep; the next cycle retries it.
                log.warn("Polling video task {} failed: {}", task.getTaskKey(), exception.getMessage());
            }
        }
    }
}

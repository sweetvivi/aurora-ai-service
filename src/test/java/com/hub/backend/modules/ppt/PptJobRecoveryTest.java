package com.hub.backend.modules.ppt;

import com.hub.backend.modules.ppt.entity.PptJobEntity;
import com.hub.backend.modules.ppt.repository.PptJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领约、续租与判死三件事的数据库语义。
 *
 * <p>这些方法全是带条件的定向 UPDATE，写错条件不会编译失败，只会在运行时悄悄放过一个任务
 * （或者把正在跑的任务判死），所以逐条钉住：领取只能有一个赢家、进度写入顺带续租、租约过期
 * 才判死、老数据没有租约时按 updated_at 兜底。</p>
 *
 * <p>只测仓储层，不走 {@code runJob} —— 那一步会真去调上游生成。</p>
 */
@SpringBootTest
@Transactional
class PptJobRecoveryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0, 0);

    @Autowired
    private PptJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    private PptJobEntity newJob(String jobId) {
        return jobs.saveAndFlush(new PptJobEntity(jobId, 1L, "测试演示文稿"));
    }

    @Test
    void onlyOneWorkerCanClaimAQueuedJob() {
        PptJobEntity job = newJob("ppt_job_claim");

        int first = jobs.claimForExecution(job.getJobId(), "worker-a", NOW.plusMinutes(90), "生成中", NOW);
        int second = jobs.claimForExecution(job.getJobId(), "worker-b", NOW.plusMinutes(90), "生成中", NOW);

        assertThat(first).isEqualTo(1);
        // 第二个 worker 必须拿到 0：否则两个线程会各跑一次生成，用户被算两次费用。
        assertThat(second).isZero();
        PptJobEntity claimed = jobs.findById(job.getJobId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(PptJobEntity.STATUS_PARSING);
        assertThat(claimed.getLeaseOwner()).isEqualTo("worker-a");
    }

    @Test
    void aClaimedJobCanNoLongerBeClaimedEvenIfItsLeaseIsFresh() {
        PptJobEntity job = newJob("ppt_job_claim_twice");
        jobs.claimForExecution(job.getJobId(), "worker-a", NOW.plusMinutes(90), "生成中", NOW);
        advance(job.getJobId(), "status = 'queued'", null);

        // 回到 queued 才允许重新领取：这正是「线程池拒收后归还」的路径。
        assertThat(jobs.claimForExecution(job.getJobId(), "worker-b", NOW.plusMinutes(90), "生成中", NOW))
                .isEqualTo(1);
    }

    @Test
    void progressWriteRenewsTheLease() {
        PptJobEntity job = newJob("ppt_job_renew");
        jobs.claimForExecution(job.getJobId(), "worker-a", NOW.plusMinutes(1), "生成中", NOW);

        jobs.applyProgress(job.getJobId(), PptJobEntity.STATUS_RENDERING, 65, "编排中",
                NOW.plusMinutes(90), NOW);

        PptJobEntity row = jobs.findById(job.getJobId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PptJobEntity.STATUS_RENDERING);
        // 一次缓慢的上游调用期间只有进度写入能证明它还活着，所以租约必须跟着延到 90 分钟。
        assertThat(row.getLeaseUntil()).isAfter(NOW.plusMinutes(89));
    }

    @Test
    void staleQueuedJobsAreFoundButFreshOnesAreNot() {
        newJob("ppt_job_queued_fresh");
        PptJobEntity stale = newJob("ppt_job_queued_stale");
        advance(stale.getJobId(), "updated_at = ?", NOW.minusMinutes(30));

        List<PptJobEntity> found = jobs.findStaleQueued(
                NOW.minusMinutes(2), NOW.minusHours(24), PageRequest.of(0, 20));

        assertThat(found).extracting(PptJobEntity::getJobId).contains("ppt_job_queued_stale");
        assertThat(found).extracting(PptJobEntity::getJobId).doesNotContain("ppt_job_queued_fresh");
    }

    @Test
    void anOldQueuedJobIsFailedInsteadOfResurrected() {
        PptJobEntity ancient = newJob("ppt_job_queued_ancient");
        jdbc.update("update ppt_jobs set created_at = ?, updated_at = ? where job_id = ?",
                NOW.minusDays(3), NOW.minusDays(3), ancient.getJobId());

        // 修复重启丢任务之后，这些历史行会第一次被扫到；它们不该在部署当天突然开始生成。
        assertThat(jobs.failAbandonedQueued(NOW.minusHours(24), "任务已过期", NOW)).isEqualTo(1);
        assertThat(jobs.findById(ancient.getJobId()).orElseThrow().getStatus())
                .isEqualTo(PptJobEntity.STATUS_FAILED);
        assertThat(jobs.findStaleQueued(NOW.minusMinutes(2), NOW.minusHours(24), PageRequest.of(0, 20)))
                .extracting(PptJobEntity::getJobId)
                .doesNotContain("ppt_job_queued_ancient");
    }

    @Test
    void anExpiredLeaseMarksTheJobFailed() {
        PptJobEntity job = newJob("ppt_job_expired");
        // 领取时就把租约写到过去，等价于「执行者已经退出」。
        jobs.claimForExecution(job.getJobId(), "worker-a", NOW.minusMinutes(1), "生成中", NOW);

        int reaped = jobs.failStuck(PptJobEntity.STARTED_STATUSES, "任务已中断", NOW, NOW.minusMinutes(90));

        assertThat(reaped).isEqualTo(1);
        PptJobEntity row = jobs.findById(job.getJobId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PptJobEntity.STATUS_FAILED);
        assertThat(row.getErrorMessage()).isEqualTo("任务已中断");
        assertThat(row.getLeaseUntil()).isNull();
    }

    @Test
    void aRunningJobWithAFreshLeaseIsLeftAlone() {
        PptJobEntity job = newJob("ppt_job_running");
        jobs.claimForExecution(job.getJobId(), "worker-a", NOW.plusMinutes(60), "生成中", NOW);

        assertThat(jobs.failStuck(PptJobEntity.STARTED_STATUSES, "任务已中断", NOW, NOW.minusMinutes(90)))
                .isZero();
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus())
                .isEqualTo(PptJobEntity.STATUS_PARSING);
    }

    @Test
    void legacyRowsWithoutALeaseAreJudgedByTheirLastUpdate() {
        PptJobEntity job = newJob("ppt_job_legacy");
        jobs.claimForExecution(job.getJobId(), "worker-a", NOW.plusMinutes(60), "生成中", NOW);
        // 本迁移之前创建的行没有租约列的值，只能用 updated_at 兜底。
        advance(job.getJobId(), "lease_owner = NULL, lease_until = NULL, updated_at = ?", NOW.minusHours(3));

        assertThat(jobs.failStuck(PptJobEntity.STARTED_STATUSES, "任务已中断", NOW, NOW.minusMinutes(90)))
                .isEqualTo(1);
    }

    @Test
    void aQueuedJobIsNeverFailedByTheLeaseSweep() {
        PptJobEntity job = newJob("ppt_job_queued_old");
        advance(job.getJobId(), "updated_at = ?", NOW.minusHours(5));

        // queued 不在「已开始」集合里：它应该被重新提交，而不是被判死。
        assertThat(jobs.failStuck(PptJobEntity.STARTED_STATUSES, "任务已中断", NOW, NOW.minusMinutes(90)))
                .isZero();
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus())
                .isEqualTo(PptJobEntity.STATUS_QUEUED);
    }

    @Test
    void releasingAClaimPutsTheJobBackInTheQueue() {
        PptJobEntity job = newJob("ppt_job_release");
        jobs.claimForExecution(job.getJobId(), "worker-a", NOW.plusMinutes(90), "生成中", NOW);

        assertThat(jobs.releaseClaim(job.getJobId(), "worker-b", "排队中", NOW)).isZero();
        assertThat(jobs.releaseClaim(job.getJobId(), "worker-a", "排队中", NOW)).isEqualTo(1);

        PptJobEntity row = jobs.findById(job.getJobId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PptJobEntity.STATUS_QUEUED);
        assertThat(row.getLeaseOwner()).isNull();
        assertThat(row.getLeaseUntil()).isNull();
    }

    /**
     * 直接改时间列：{@code updated_at} 带 {@code @UpdateTimestamp}，走实体保存会被覆盖成当前时间，
     * 造不出「很久以前」的行。
     */
    private void advance(String jobId, String assignment, LocalDateTime value) {
        String sql = "update ppt_jobs set " + assignment + " where job_id = ?";
        if (value == null) {
            jdbc.update(sql, jobId);
        } else {
            jdbc.update(sql, value, jobId);
        }
    }
}

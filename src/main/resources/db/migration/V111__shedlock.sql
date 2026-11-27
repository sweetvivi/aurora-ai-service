-- 定时任务的多实例互斥表（ShedLock）。
--
-- 为什么需要它：@Scheduled 是「每个进程各自跑」。部署两个副本之后，
--   * NewApiCatalogSyncScheduler 每 5 分钟各拉一次全量快照，两边同时 upsert 同一批行；
--   * VideoTaskPoller 每 5 秒各扫一遍同一批任务，各打一次上游状态接口 —— 提交有 CAS 保护，
--     但状态查询没有，等于把上游调用量翻倍。
-- 抢锁失败的一方直接跳过这一轮，不排队、不重试：定时任务错过一轮，下一轮照常。
--
-- 不锁的两个任务是有意的：
--   * PptJobRecoveryScheduler：领取是 queued→parsing 的条件 UPDATE，判死是幂等批量更新；
--   * TaskQueueDispatcher：同理，claim 用 status='pending' 做 CAS。
-- 它们的语义本来就是「谁抢到谁做」，再加一把锁只会让恢复变慢。
--
-- 列类型遵循 ShedLock 官方 DDL：TIMESTAMP(3)。它支持 DATETIME，但用官方形态最省事。
-- 这张表由 ShedLock 自己维护（行会一直存在，只是 lock_until 过期），业务不要写它。

CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NULL,
    locked_at TIMESTAMP(3) NULL,
    locked_by VARCHAR(255) NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

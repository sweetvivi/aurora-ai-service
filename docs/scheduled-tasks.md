# 定时任务与多实例

> 代码：`common/config/SchedulingLockConfig`、`docs/queue.md`（队列派发不在本文范围）  
> 表：`shedlock`（`V106`）

## 现状

| 任务       | 位置                                            | 频率   | 多实例保护                                       | 为什么                                                                         |
| -------- | --------------------------------------------- | ---- | ------------------------------------------- | --------------------------------------------------------------------------- |
| 视频状态轮询   | `VideoTaskPoller`                             | 5s   | `@SchedulerLock("video-task-poll")`，15m     | 提交有 `claimPendingForSubmit` 的 CAS，**状态查询没有** —— 两个实例各轮一遍，上游调用翻倍，进度还会被先后写入改乱 |
| 目录全量同步   | `NewApiCatalogSyncScheduler.syncPeriodically` | 5min | `@SchedulerLock("newapi-catalog-sync")`，30m | 全量快照 upsert 同一批行，两个实例同时跑等于互相覆盖；进程内 `synchronized` 挡不住第二个副本                  |
| PPT 恢复扫描 | `PptJobRecoveryScheduler`                     | 30s  | **不加锁（设计如此）**                               | 领取是 `queued → parsing` 的条件 UPDATE，判死是幂等批量更新；多实例只会更早把任务捡回来                   |
| 队列派发     | `TaskQueueDispatcher`                         | 2s   | **不加锁（设计如此）**                               | `claim` 用 `status='pending'` 做 CAS，抢不到的实例自然空手                               |

启动同步（`syncOnStartup`）刻意**不加锁**：新实例必须一启动就有可用目录，抢不到锁就跳过会让它长时间读到空表。代价是多实例启动时各自拉一遍，可接受。

## 为什么用 ShedLock 而不是自己写

自己的锁要么睡在内存里（多实例无效），要么又是一张表 + 一套条件 UPDATE。ShedLock 只做一件事：抢不到锁就跳过这次调用，不排队、不重试 —— 正好是定时任务要的语义。它用 `jdbc-template` provider，不引入 Redis/Mongo。

**`usingDbTime()`**：租约比较用数据库时间。NAS/容器时钟漂移几十秒很常见，用本机时间会让「锁到期没到期」在两台机器上得出不同结论，结果就是两个实例同时跑。

**`lockAtMostFor` 必须大于该任务的最坏耗时**，否则锁提前释放、第二个实例插进来。视频轮询取 15 分钟（批 50、单个任务可能卡到分钟级），目录同步取 30 分钟。真超了也不会坏数据 —— 只是多一遍重复劳动。

⚠️ `shedlock.lock_until` 存的是 **UTC**（ShedLock 的 `usingDbTime()` 约定），与业务表的 `DATETIME(6)`（本地时区）对不上。排查时别直接拿它和 `created_at` 比大小。

## 起第二个副本前的清单

- [x] 定时任务互斥（本文）
- [x] 跨实例停止生成：`REDIS_ENABLED=true`，取消信号经 Redis pub/sub 送到当事实例（`docs/redis.md`）
- [x] 限流不再按实例计数：同一个开关下计数迁到 Redis，`limit` 就是全站额度
- [x] PPT 建单准入在单实例内精确：计数与插入放在同一把锁里，锁持有到事务提交
      （`PptJobCapacity.withAdmission`）。多副本下仍是近似 —— 真正的上限由执行器线程池与
      `claimForExecution` 的 CAS 决定，这条额度只负责早点回 429
- [x] 日志带实例标识：`[hostname:pid]`，见下节

## 日志里的实例标识

每行日志形如：

```
2026-11-11 09:46:45.709  INFO [hub-test-backend:1] [           main] c.h.b... : Started ...
```

`hostname:pid` 由 `InstanceIdentity` 在 `main` 里算好、写进系统属性，`logback-spring.xml`
读它拼进 pattern。之所以不能只看 PID：容器里每个副本的 JVM 都从 1 开始，PID 在多副本下等于没有。

Docker 里 `HOSTNAME` 是容器 id（compose 下通常是「服务名 + 序号」），因此多副本时这一列能直接
区分「这条定时任务是谁跑的」。想固定成可读的名字，设 `APP_INSTANCE=backend-1` 即可。

## 验证方式

```bash
# 锁表里应出现两条记录，locked_by 是抢到锁的实例
mysql -uroot -p backend -e "select name, lock_until, locked_by from shedlock"
```

`SchedulerLockTest` 用 `lockAtLeastFor=30s` 造出确定窗口：同一方法连调两次只执行一次，且 `shedlock` 表里有对应行；另有一条用例验证 `lockAtMostFor` 可以来自配置占位符（生产代码正是这种写法）。

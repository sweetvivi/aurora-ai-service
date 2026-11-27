-- 通用任务队列：把「必须送达、失败要重试」的副作用从进程内回调搬到库里。
--
-- 起因是文件删除：blob 的删除挂在 afterCommit 上，回调里再 try/catch 吞掉异常。进程如果在
-- 提交之后、删盘之前退出，或者存储短暂不可用，那个对象就永远留在磁盘上 —— 没有任何地方记得
-- 它该被删。同类问题在「通知」「计费补偿」上都会重复出现，所以这次不针对单个业务打补丁。
--
-- 设计要点（与换 RabbitMQ 之后的语义保持一致）：
--   * publish 与业务写在**同一个事务**里（事务性发件箱）：业务回滚，消息跟着消失；
--     业务提交，消息一定存在。不依赖 afterCommit —— 那一步丢了就丢了。
--   * claim 用条件 UPDATE 抢占（status='pending' → 'claimed'），多实例下只有一个赢家。
--   * 租约到期没 ack：attempts 已在上次 claim 时 +1，回到 pending 等下一次，或到上限判失败。
--   * 不做消息去重：投递语义是至少一次，幂等由处理器负责（删不存在的对象本来就不是错误）。
--     需要去重时是在消费端按业务键去重，而不是在队列里堆唯一索引 —— 那会让「同一件事合法地再发生一次」变得不可能。
--
-- payload 是调用方自定的不透明字符串（本项目用 JSON），队列不解析它 —— 换成 RabbitMQ 时
-- 它原样成为消息体，业务代码不需要改。

CREATE TABLE IF NOT EXISTS task_queue (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    queue_name VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_task_queue_claim (queue_name, status, available_at),
    KEY idx_task_queue_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

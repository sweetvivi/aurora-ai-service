-- V103: 注销时远端账号没删成的「待办」。
--
-- 背景：本地注销要连带删掉 New API 里的那个用户行（否则余额、身份、用户名都留在上游）。
-- New API 对 /api/verify 及一批关键接口有 20 次 / 20 分钟的限流，批量注销时很快会撞上 429。
-- 以前这种情况只写一行 warn 就结束：本地账号没了，远端账号活着，用户名还被永久占用 ——
-- 既没法重名注册，也没人再去收拾它。这张表把这次失败记成一条任务，由后台定时器在限流
-- 窗口过去之后继续删，直到删净或超过重试上限。
--
-- 设计取舍：
--   * 不建指向 user_accounts 的外键：本地行在入队之后就被物理删除了，任务必须比它活得久。
--     所以 local_user_id 只作追溯用，不约束。
--   * newapi_user_id 可空：自助注销（管理员没配置时）先 login 才知道远端 id，入队时可能还没有，
--     那种情况只靠 username + password 重放。
--   * password_ciphertext 用 TINYBLOB + AES-GCM（与 user_gateway_tokens 的 PAT 同一套密钥）：
--     New API 的自助注销要密码做 step-up 验证，明文落库不可接受；任务成功后整行删除，
--     密文的生命周期就是「限流窗口」这几分钟到几小时。
--   * mode 记录当次走的是哪条路：ADMIN（DELETE /api/user/{id}，物理删行）还是 SELF
--     （用户自己的 DELETE /api/user/self，仅打软删标记）。重试沿用原路径，不擅自换招 ——
--     换招会多打一次 /api/verify，正好又撞限流。
--   * 不加唯一索引：同一次注销在并发下可能入队两次，重复的硬删只是多一次 404（按成功处理），
--     删表比让注销接口因为唯一键冲突整体回滚划算得多。
--   * status 只有 PENDING / RUNNING / FAILED。成功即删行 —— 注销成功本身已经记在审计与
--     访问日志里，这里不需要再留一份历史。
--
-- Additive only：不删不改任何已有表。

CREATE TABLE IF NOT EXISTS newapi_account_deletion_tasks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- 本地已被删除的用户 id，仅作追溯
    local_user_id BIGINT UNSIGNED NOT NULL,
    -- New API 用户 id；自助路径入队时可能还不知道
    newapi_user_id BIGINT NULL,
    -- New API 用户名，自助路径登录要用
    username VARCHAR(128) NULL,
    -- ADMIN / SELF
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(512) NULL,
    -- 自助路径的密码密文，AES-GCM；ADMIN 路径为空
    password_ciphertext TINYBLOB NULL,
    password_nonce VARBINARY(12) NULL,
    password_tag VARBINARY(16) NULL,
    -- 认领租约：多实例部署时靠它保证一次只有一个实例在删同一个账号
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_newapi_deletion_due (status, next_attempt_at),
    KEY idx_newapi_deletion_local_user (local_user_id),
    KEY idx_newapi_deletion_newapi_user (newapi_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

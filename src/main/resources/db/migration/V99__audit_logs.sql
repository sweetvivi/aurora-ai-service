-- 关键数据变更审计。一次写操作一行，记录「谁、在什么请求里、把哪个对象的哪些字段，从什么改成了什么」。
--
-- 与 request_logs 的分工：
--   request_logs = 每个 HTTP 请求一行（有没有人调、调得成不成、多快）；
--   audit_logs   = 每次**数据变更**一行（改了什么、改前是什么值、改后是什么值）。
-- 一个请求可能产生 0 条（只读）或 N 条（批量操作）审计记录，所以不能合并成一张表。
--
-- 几个必须写进表里的东西：
--   * before_json / after_json：光有「改过」无法追责，也回滚不了。余额、模型上架状态这类
--     字段，出事时第一个问题就是「原来是多少」。
--   * changed_json：字段级 diff。整份快照太大也难读，把变化的路径单独抽出来，
--     一条 SQL 就能回答「这个模型的价格什么时候被改过」。
--   * outcome：失败的操作也要留痕。删模型失败、改状态被拒，同样是审计对象。
--   * actor_type / actor_id：管理端现在用固定密钥鉴权，没有操作人账号，先记 ADMIN_KEY + 来源 IP；
--     将来接入管理端账号体系时，这一列直接换成真实操作人 id，不用改表。
--
-- 不建外键到任何业务表：审计记录必须比它审计的对象活得久（账号会注销、模型会被删）。

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(32) NULL,
    action VARCHAR(64) NOT NULL,
    target VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id VARCHAR(64) NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    http_method VARCHAR(10) NULL,
    route VARCHAR(255) NULL,
    before_json TEXT NULL,
    after_json TEXT NULL,
    changed_json TEXT NULL,
    outcome VARCHAR(16) NOT NULL,
    error_message VARCHAR(1024) NULL,
    duration_ms INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_logs_time (occurred_at),
    KEY idx_audit_logs_target (target, target_id, occurred_at),
    KEY idx_audit_logs_action (action, occurred_at),
    KEY idx_audit_logs_actor (actor_type, actor_id, occurred_at),
    KEY idx_audit_logs_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

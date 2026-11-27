-- 请求级访问日志。一次 HTTP 请求一行，用于事故复盘与用户问题定位。
--
-- 为什么不写日志文件而落库：
--   1. 线上是 docker 部署，容器重建即丢文件；落库才能跨重启追溯；
--   2. 事故排查的第一问几乎总是「哪个用户、哪个接口、什么错」，SQL 过滤比 grep 快；
--   3. 运营/客服需要按用户查，文件方案没法给非技术同学用。
--
-- 三个刻意的设计取舍：
--   * 不建外键到 user_accounts。注销流程会物理删除账号行（见 AuthService#deleteAccount），
--     外键要么挡住注销、要么 ON DELETE CASCADE 把审计记录一起删掉——两种都不能接受。
--     代价是 user_id 可能指向已不存在的账号，这是审计日志应有的语义。
--   * 不加 updated_at。日志只写不改，多一列就多一次写入开销。
--   * request_body / error_details 是 TEXT 而非 JSON 类型：MySQL 的 JSON 列在批量插入时
--     会做格式校验，而日志系统宁可存一段「格式可疑的原文」也不能因为一条脏数据丢整批。
--
-- 索引全部以「时间 + 维度」组合，覆盖排查时的三种访问路径：
--   按用户翻历史、按状态码捞错误、按接口看耗时分布。单独的 trace_id 索引用于全链路串查。

CREATE TABLE IF NOT EXISTS request_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    trace_id VARCHAR(32) NOT NULL,
    request_time DATETIME(6) NOT NULL,
    level VARCHAR(8) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    site_id BIGINT UNSIGNED NULL,
    client_ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    browser VARCHAR(64) NULL,
    os VARCHAR(64) NULL,
    http_method VARCHAR(10) NOT NULL,
    route VARCHAR(255) NULL,
    uri VARCHAR(512) NOT NULL,
    query_string VARCHAR(1024) NULL,
    request_body TEXT NULL,
    response_status SMALLINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    error_details TEXT NULL,
    duration_ms INT NOT NULL,
    upstream_json TEXT NULL,
    biz_type VARCHAR(32) NULL,
    biz_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_request_logs_time (request_time),
    KEY idx_request_logs_trace (trace_id),
    KEY idx_request_logs_user_time (user_id, request_time),
    KEY idx_request_logs_status_time (response_status, request_time),
    KEY idx_request_logs_route_time (route, request_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

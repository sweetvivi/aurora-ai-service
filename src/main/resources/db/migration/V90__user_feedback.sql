-- 用户意见反馈（V90）
--
-- 入口：前台 /feedback 页面（左侧栏「意见反馈」，位于「当前余额」上方）。
-- 登录是可选的：带令牌提交时记下 user_id / site_id，方便联系与定位账号问题；
-- 游客也能提交，所以这两列允许为空。
--
-- status 先只留一个 NEW 占位，后台处理流程（已读 / 已回复）后续再补，避免现在就把枚举定死。

CREATE TABLE IF NOT EXISTS user_feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    site_id BIGINT NULL,
    category VARCHAR(32) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    contact VARCHAR(120) NULL,
    page_url VARCHAR(512) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_user_feedback_created (created_at DESC, id DESC),
    KEY idx_user_feedback_user (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

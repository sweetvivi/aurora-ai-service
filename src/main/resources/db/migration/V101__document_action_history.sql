-- V99: 文档处理历史。
--
-- 页面 /doc-processing 的「处理结果」之前只活在内存里：刷新即失，也没法回过头查
-- 「上周把这份 PDF 转成了什么」。这张表把每次工具执行落一行，支持按文档 / 动作 / 关键词回查。
--
-- 设计取舍：
--   * 不建指向 documents 的外键。文档是软删且可以被清理，历史不应该跟着消失；
--     文档名在同一行做快照（document_name），删了文档也还能看出当时处理的是哪一份。
--   * 也不建指向 uploaded_files 的外键。产物附件可能被单独清理，此时历史仍要可查，
--     下载失败由 /files/{id}/content 的 404 体现，比丢历史更好。
--   * result_text 用 LONGTEXT：提取类动作一次能出几万字（上限 6 万），TEXT 的 64KB 不够。
--   * 失败也记（status=FAILED + error_message）。用户看到的「处理没有完成」需要能回溯，
--     否则只留下一次莫名其妙的空白结果。
--
-- Additive only：不删不改任何已有表。

CREATE TABLE IF NOT EXISTS document_action_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NULL,
    -- 原文档 id；文档被删除后仍保留，用于「仅当前文档」筛选
    document_id BIGINT UNSIGNED NULL,
    -- 文档名快照，不随文档改名 / 删除变化
    document_name VARCHAR(512) NOT NULL,
    document_format VARCHAR(16) NOT NULL DEFAULT '',
    -- 动作 key（与 DocumentActionService.LABELS 的 key 一致）
    action VARCHAR(48) NOT NULL,
    -- 当时的展示名，动作改名后历史仍显示用户看到的名字
    label VARCHAR(64) NOT NULL,
    -- 请求参数快照，例如 {"dpi":"150"}；空参数为 NULL
    options_json VARCHAR(1024) NULL,
    -- SUCCESS / FAILED
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    error_message VARCHAR(512) NULL,
    result_text LONGTEXT NULL,
    -- result_text 的字符数。列表要显示「1,234 字」，但列表查询绝不能把 LONGTEXT 一起读出来：
    -- 一页 20 条 AI 结果就是几百 KB 到 1 MB，纯属浪费。写入时算好，列表只读这一列。
    result_chars INT NOT NULL DEFAULT 0,
    -- 产物附件：文件类动作才有；文本类 / 导出类为 NULL
    file_id BIGINT UNSIGNED NULL,
    file_name VARCHAR(255) NULL,
    file_format VARCHAR(16) NULL,
    truncated TINYINT(1) NOT NULL DEFAULT 0,
    duration_ms INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 列表默认「我的、最新的在前」
    KEY idx_dah_owner_time (user_id, created_at),
    -- 「仅当前文档」
    KEY idx_dah_owner_doc (user_id, document_id, created_at),
    -- 按工具筛选
    KEY idx_dah_owner_action (user_id, action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='文档处理动作的执行历史（结果快照）';

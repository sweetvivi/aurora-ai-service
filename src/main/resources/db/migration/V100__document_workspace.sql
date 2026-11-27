-- V98: 文档处理工作区。
--
-- 页面 /doc-processing 之前是纯前端演示（上传、转换、总结全是 setTimeout 假结果），
-- 这里给它一张真正的表：原文落在 uploaded_files（复用既有的白名单 / 配额 / 校验和），
-- 本表只记「这份文档属于谁、抽出来多少字、正文是什么」。
--
-- 正文单独存一列而不是每次重新解析：
--   * 解析 PDF/Word 要几百毫秒到数秒，工具点一次就重解一次太浪费；
--   * AI 动作、文档对话都要拿同一份正文，重新解析可能得到不同结果（字体、换行差异）。
-- LONGTEXT 而不是 TEXT：30MB 的表格转文本很容易超过 64KB。
--
-- Additive only：不删不改任何已有表。

CREATE TABLE IF NOT EXISTS documents (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NULL,
    -- 原始文件在 uploaded_files 里的行；示例文档没有上传文件，为 NULL
    file_id BIGINT UNSIGNED NULL,
    original_name VARCHAR(512) NOT NULL,
    extension VARCHAR(16) NOT NULL,
    -- 页面上的四个格式标签：PDF / Word / Excel / TXT
    format VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    -- PDF 才有页数，其它格式为 NULL
    page_count INT NULL,
    char_count INT NOT NULL DEFAULT 0,
    content LONGTEXT NULL,
    -- UPLOAD 真实上传 / SAMPLE 页面里的示例文档
    source VARCHAR(16) NOT NULL DEFAULT 'UPLOAD',
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_documents_owner (user_id, status, created_at),
    CONSTRAINT fk_documents_file FOREIGN KEY (file_id) REFERENCES uploaded_files (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='文档处理工作区的文档索引与抽取正文';

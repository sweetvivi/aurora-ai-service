-- ---------------------------------------------------------------------------------------------
-- 论文写作：稿件表
--
-- 背景：前端工具台（生成器卡片 → 三级大纲 → 全文）需要一个可保存的稿件实体。大纲是
-- 「改名 / 加小节 / 删节点」高频整体重写的树，没有任何单节点查询，因此整棵树存 JSON，
-- 而不是拆成 thesis_outline_nodes 表：一次保存就是一次 UPDATE，天然原子，也不会出现
-- 「删了一半」的中间态。
--
-- 约定：
--   status            OUTLINE / GENERATING / COMPLETED / DELETED（软删除，保留审计）
--   outline_version   每次保存 +1，前端可据此判断是否有别处覆盖
--   target_words      生成器卡片的字数档位（8000 / 12000 / 20000 / 30000）
--   site_id           与其它业务表一致，指向 app_sites，便于白标多站点隔离
-- ---------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS thesis_documents (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NULL,
    topic VARCHAR(255) NOT NULL,
    tier VARCHAR(32) NOT NULL,
    discipline VARCHAR(64) NOT NULL,
    major VARCHAR(128) NOT NULL,
    target_words INT NOT NULL DEFAULT 8000,
    reference_enabled TINYINT(1) NOT NULL DEFAULT 1,
    attachments_enabled TINYINT(1) NOT NULL DEFAULT 1,
    data_model_enabled TINYINT(1) NOT NULL DEFAULT 0,
    outline_json LONGTEXT NOT NULL,
    outline_version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'OUTLINE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_thesis_documents_user_updated (user_id, updated_at),
    KEY idx_thesis_documents_status_created (status, created_at),
    CONSTRAINT fk_thesis_documents_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 站内 AI 客服挂件：配置、FAQ、知识文档分块；反馈表扩展转人工字段；种子 support-bot Agent。

CREATE TABLE IF NOT EXISTS support_bot_config (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT NULL,
    agent_sn VARCHAR(64) NOT NULL DEFAULT 'support-bot',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    online_mode VARCHAR(32) NOT NULL DEFAULT 'ALWAYS',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    schedule_json TEXT NULL,
    welcome_title VARCHAR(255) NOT NULL DEFAULT '欢迎',
    welcome_description VARCHAR(1024) NOT NULL DEFAULT '有问题随时问我，也可以转人工处理。',
    home_title VARCHAR(255) NOT NULL DEFAULT 'AI 助手',
    brand_name VARCHAR(128) NOT NULL DEFAULT 'AI 客服',
    brand_color VARCHAR(32) NOT NULL DEFAULT '#16a34a',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_support_bot_config_site (site_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS support_faq (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT NULL,
    question VARCHAR(512) NOT NULL,
    answer TEXT NOT NULL,
    keywords VARCHAR(512) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_support_faq_site_enabled (site_id, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS support_docs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NULL,
    raw_text LONGTEXT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_support_docs_site_enabled (site_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS support_doc_chunks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    doc_id BIGINT UNSIGNED NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_support_doc_chunks_doc (doc_id, chunk_index),
    CONSTRAINT fk_support_doc_chunks_doc
        FOREIGN KEY (doc_id) REFERENCES support_docs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE user_feedback
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'FEEDBACK' AFTER page_url,
    ADD COLUMN agent_conversation_id BIGINT NULL AFTER source,
    ADD COLUMN transcript_snapshot MEDIUMTEXT NULL AFTER agent_conversation_id;

CREATE INDEX idx_user_feedback_source ON user_feedback (source, created_at DESC);

INSERT INTO support_bot_config (
    site_id, agent_sn, enabled, online_mode, timezone,
    welcome_title, welcome_description, home_title, brand_name, brand_color
)
SELECT site.id, 'support-bot', 1, 'ALWAYS', 'Asia/Shanghai',
       '欢迎', '有问题随时问我，也可以转人工处理。',
       'AI 助手', 'AI 客服', '#16a34a'
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_bot_config c WHERE c.site_id = site.id
  );

INSERT INTO agents (
    site_id, category_id, owner_user_id, agent_key, sn, agent_type, ui_type, scope,
    classify, child_classify, chat_suggest, name, description, system_prompt, chat_prologue,
    source_path, ui_config_json, visibility, publish_status, score, see_count, use_count,
    favorite_count, is_hot, published_at, status, sort_order
)
SELECT site.id, NULL, NULL, 'support-bot', 'support-bot', 'OFFICIAL', 1, 1,
       '客服', NULL, 0,
       'AI 客服助手',
       '站内 AI 客服，基于 FAQ 与知识文档回答产品问题，必要时引导转人工。',
       '你是本站的 AI 客服助手。优先依据系统提供的「知识库摘录」回答用户关于产品、功能、账号与服务的问题。\n规则：\n1. 知识库有依据时简洁准确作答，可引用要点；\n2. 知识库不足时明确说明不确定，并建议用户转人工；\n3. 用户明确要求人工客服、投诉、退款争议时，引导其点击「转人工」；\n4. 不要编造未提供的政策、价格或内部数据；\n5. 语气友好、专业，使用中文简短回复。',
       '你好！我是 AI 客服助手，可以帮你解答产品、功能和服务相关问题。请问有什么可以帮你的？',
       '内置客服', NULL, 'PUBLIC', 'PUBLISHED', 5.00, 0, 0,
       0, 0, CURRENT_TIMESTAMP(6), 'ACTIVE', 0
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM agents a
      WHERE a.site_id = site.id AND a.agent_key = 'support-bot'
  );

INSERT INTO support_faq (site_id, question, answer, keywords, enabled, sort_order)
SELECT site.id,
       '如何联系人工客服？',
       '在客服挂件中打开「帮助中心」或「聊天」，点击「转人工」，填写问题与联系方式后提交，我们的同事会尽快处理。',
       '人工,客服,转人工',
       1, 0
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_faq f
      WHERE f.site_id = site.id AND f.question = '如何联系人工客服？'
  );

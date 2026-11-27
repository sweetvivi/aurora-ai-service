-- V9: agent platform schema.
--
-- Models the "超级智能体" surface at https://ai1foo.com/customAgent/index:
--   * official / my-created / favourites tabs, categories, search and hot sorting
--   * per-agent prompt, intro, avatar, labels, opening line, suggested questions,
--     selectable models and optional tool ("能力") grants
--   * agent conversations and their messages kept in their own tables, away from the
--     generic conversations / chat_messages used by /newchat
--
-- Field provenance from the page bundle (the API is auth-gated, so the shape was read from the
-- published front-end chunks):
--   GET  /api/v2/chat/agents/records_lists      { page, rows }        agent list, paged
--   POST /api/v2/chat/agents/delete_records     { chat_id }          agent conversation delete
--   POST /api/v1/chat/record/list               { model, page, rows } conversation history
--   POST /api/v1/chat/record/delete, GET /api/v1/chat/record/clear   conversation maintenance
--   POST /api/v2/chat/groups/{add,reset,delete,add_models,record/lists,record/delete}
--   The create form maps 1:1 onto: sn, name, intro, prompt, avatar, labels, ui_config.forms,
--   ui_config.chat_prologue, ui_config.tip_struct, ui_config.exampleUrls, call_tools, chat_questions,
--   chat_suggest, ui_type, score, seeCount, is_collect.
--
-- Additive only: no existing table, column, index or row is dropped or renamed.

-- --------------------------------------------------------------------------------------------
-- Categories drive the "官方智能体" tab and the search filter row.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_categories (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    category_key VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    icon_url VARCHAR(1024) NULL,
    description VARCHAR(512) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_categories_site_key (site_id, category_key),
    KEY idx_agent_categories_catalog (site_id, status, sort_order),
    CONSTRAINT fk_agent_categories_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- V1 already created `agents` with key/name/description/icon/system_prompt/config/status.
-- The page needs more than that, so the table is widened in place instead of forking a second
-- agent table: `description` is the page's `intro`, `icon_url` is its `avatar`, `system_prompt`
-- is its `prompt` (配置信息).
-- --------------------------------------------------------------------------------------------
ALTER TABLE agents
    ADD COLUMN category_id BIGINT UNSIGNED NULL AFTER site_id,
    -- NULL owner means "published by the platform"; the "我创建的" tab filters owner_user_id.
    ADD COLUMN owner_user_id BIGINT UNSIGNED NULL AFTER category_id,
    -- External identifier used by the front-end route /customDetail/index?id={sn}. Nullable so
    -- rows created before this migration stay valid; MySQL keeps multiple NULLs out of the unique key.
    ADD COLUMN sn VARCHAR(64) NULL AFTER agent_key,
    -- OFFICIAL: platform published (page type "common"). CUSTOM: user created (page type "custom").
    ADD COLUMN agent_type VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL' AFTER sn,
    ADD COLUMN chat_prologue LONGTEXT NULL AFTER system_prompt,
    -- Original model hint of an imported prompt (the library uses sonnet/haiku). NULL when unknown.
    ADD COLUMN source_model VARCHAR(64) NULL AFTER chat_prologue,
    -- Import provenance, e.g. "编程语言专家/Python专家.md". Lets a re-import be traced to its source.
    ADD COLUMN source_path VARCHAR(512) NULL AFTER source_model,
    -- Low-frequency presentation payload (ui_config.forms / tip_struct / exampleUrls), kept as JSON
    -- and deliberately NOT mapped by JPA -- see docs/database-schema.md on JSON columns.
    ADD COLUMN ui_config_json JSON NULL AFTER source_path,
    ADD COLUMN visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC' AFTER ui_config_json,
    ADD COLUMN publish_status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' AFTER visibility,
    ADD COLUMN score DECIMAL(4, 2) NOT NULL DEFAULT 5.00 AFTER publish_status,
    ADD COLUMN see_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER score,
    ADD COLUMN use_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER see_count,
    ADD COLUMN favorite_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER use_count,
    ADD COLUMN is_hot TINYINT(1) NOT NULL DEFAULT 0 AFTER favorite_count,
    ADD COLUMN published_at DATETIME(6) NULL AFTER is_hot,
    ADD UNIQUE KEY uk_agents_sn (sn),
    ADD KEY idx_agents_catalog_category (site_id, category_id, status, sort_order),
    ADD KEY idx_agents_owner (owner_user_id, updated_at),
    ADD KEY idx_agents_name (name),
    ADD CONSTRAINT fk_agents_category FOREIGN KEY (category_id) REFERENCES agent_categories (id),
    ADD CONSTRAINT fk_agents_owner FOREIGN KEY (owner_user_id) REFERENCES user_accounts (id);

-- --------------------------------------------------------------------------------------------
-- Tags (page: labels). Separate table, because the repo does not query JSON columns.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_id BIGINT UNSIGNED NOT NULL,
    tag VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tags_agent_tag (agent_id, tag),
    KEY idx_agent_tags_tag (tag, agent_id),
    CONSTRAINT fk_agent_tags_agent FOREIGN KEY (agent_id) REFERENCES agents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- Tool grants (page: call_tools / 能力). A row is a whitelist entry, not a capability table; the
-- runtime still decides whether the tool may actually run. See docs/ai1foo-rebuild-plan.md §安全.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_abilities (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_id BIGINT UNSIGNED NOT NULL,
    ability_key VARCHAR(128) NOT NULL,
    ability_name VARCHAR(128) NULL,
    config_json JSON NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_abilities_agent_key (agent_id, ability_key),
    KEY idx_agent_abilities_key (ability_key),
    CONSTRAINT fk_agent_abilities_agent FOREIGN KEY (agent_id) REFERENCES agents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- Models an agent may run on (page: 多模型选择). Exactly one row per agent may be the default.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_models (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_id BIGINT UNSIGNED NOT NULL,
    model_id BIGINT UNSIGNED NOT NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_models_agent_model (agent_id, model_id),
    KEY idx_agent_models_agent (agent_id, sort_order),
    CONSTRAINT fk_agent_models_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT fk_agent_models_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- Opening questions (chat_questions) and follow-up suggestions (chat_suggest) share one table;
-- question_type separates them so the chat panel can load them with a single indexed query.
-- Values: OPENING / SUGGEST.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_questions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_id BIGINT UNSIGNED NOT NULL,
    question_type VARCHAR(32) NOT NULL DEFAULT 'SUGGEST',
    content VARCHAR(512) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_agent_questions_agent_type (agent_id, question_type, sort_order),
    CONSTRAINT fk_agent_questions_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT chk_agent_questions_type CHECK (question_type IN ('OPENING', 'SUGGEST'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- Favourites (page: 我收藏的 / is_collect). The counter on agents.favorite_count is a cache;
-- this table is the source of truth.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_favorites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    agent_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_favorites_user_agent (user_id, agent_id),
    KEY idx_agent_favorites_user (user_id, created_at, id),
    CONSTRAINT fk_agent_favorites_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_agent_favorites_agent FOREIGN KEY (agent_id) REFERENCES agents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- Agent conversations live here, NOT in `conversations`. Reasons:
--   * the page lists / clears / deletes agent history per agent (chat/agents/records_lists),
--     which would otherwise scan the shared table behind every /newchat list;
--   * deleting an agent chat must never touch ordinary chat rows, and vice versa;
--   * the prompt, model set and opening line can change later, so each conversation freezes the
--     agent version it started from in agent_snapshot_json;
--   * the swarm / 多智能体蜂群 surface can grow fan-out tables around this one later.
-- `conversations.agent_id` / `conversations.agent_key` from V1 stay for backwards compatibility
-- but new agent flows must not write them.
-- --------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    agent_id BIGINT UNSIGNED NOT NULL,
    model_id BIGINT UNSIGNED NULL,
    title VARCHAR(255) NULL,
    -- Frozen prompt / opening line / model of the agent at conversation start.
    agent_snapshot_json JSON NULL,
    model_params_json JSON NULL,
    message_count INT NOT NULL DEFAULT 0,
    pinned TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_message_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_agent_conversations_user_updated (user_id, updated_at, id),
    KEY idx_agent_conversations_user_agent (user_id, agent_id, updated_at, id),
    KEY idx_agent_conversations_user_pinned (user_id, pinned, updated_at, id),
    CONSTRAINT fk_agent_conversations_site FOREIGN KEY (site_id) REFERENCES app_sites (id),
    CONSTRAINT fk_agent_conversations_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_agent_conversations_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT fk_agent_conversations_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_chat_messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_conversation_id BIGINT UNSIGNED NOT NULL,
    model_id BIGINT UNSIGNED NULL,
    sequence_no INT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NULL,
    thinking_content LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    provider_message_id VARCHAR(255) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_chat_messages_sequence (agent_conversation_id, sequence_no),
    KEY idx_agent_chat_messages_conversation (agent_conversation_id, created_at, id),
    CONSTRAINT fk_agent_chat_messages_conversation FOREIGN KEY (agent_conversation_id) REFERENCES agent_conversations (id),
    CONSTRAINT fk_agent_chat_messages_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_message_feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    rating TINYINT NOT NULL,
    comment VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_message_feedback_user_message (message_id, user_id),
    KEY idx_agent_message_feedback_user_created (user_id, created_at),
    CONSTRAINT fk_agent_message_feedback_message FOREIGN KEY (message_id) REFERENCES agent_chat_messages (id),
    CONSTRAINT fk_agent_message_feedback_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_agent_message_feedback_rating CHECK (rating IN (-1, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------------------------------------------
-- agent_runs kept pointing at the generic conversation only, which no longer holds agent chats.
-- Point new runs at the separate tables and relax the old column.
-- --------------------------------------------------------------------------------------------
ALTER TABLE agent_runs
    MODIFY COLUMN conversation_id BIGINT UNSIGNED NULL,
    ADD COLUMN agent_conversation_id BIGINT UNSIGNED NULL AFTER conversation_id,
    ADD COLUMN agent_message_id BIGINT UNSIGNED NULL AFTER agent_conversation_id,
    ADD KEY idx_agent_runs_agent_conversation (agent_conversation_id, created_at),
    ADD CONSTRAINT fk_agent_runs_agent_conversation FOREIGN KEY (agent_conversation_id) REFERENCES agent_conversations (id),
    ADD CONSTRAINT fk_agent_runs_agent_message FOREIGN KEY (agent_message_id) REFERENCES agent_chat_messages (id);

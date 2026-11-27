-- Core schema for the /newchat conversation platform.
-- This migration is intentionally additive: it does not drop or rename existing objects.

CREATE TABLE IF NOT EXISTS app_sites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    domain VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    config_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_sites_site_key (site_key),
    UNIQUE KEY uk_app_sites_domain (domain),
    KEY idx_app_sites_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NOT NULL,
    username VARCHAR(128) NULL,
    email VARCHAR(255) NULL,
    phone VARCHAR(32) NULL,
    password_hash VARCHAR(255) NULL,
    nickname VARCHAR(128) NULL,
    avatar_url VARCHAR(1024) NULL,
    vip_expires_at DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_accounts_site_username (site_id, username),
    UNIQUE KEY uk_user_accounts_site_email (site_id, email),
    UNIQUE KEY uk_user_accounts_site_phone (site_id, phone),
    KEY idx_user_accounts_site_status (site_id, status),
    CONSTRAINT fk_user_accounts_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_identities (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    provider_profile_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identities_provider_subject (provider, provider_subject),
    KEY idx_user_identities_user (user_id),
    CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS auth_sessions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) NOT NULL,
    client_type VARCHAR(32) NULL,
    user_agent VARCHAR(512) NULL,
    ip_address VARCHAR(45) NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_sessions_token_hash (token_hash),
    KEY idx_auth_sessions_user_expiry (user_id, expires_at),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS verification_codes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(16) NOT NULL,
    target VARCHAR(255) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_verification_codes_lookup (site_id, channel, target, purpose, expires_at),
    CONSTRAINT fk_verification_codes_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS llm_model_groups (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    group_key VARCHAR(128) NOT NULL,
    label VARCHAR(128) NOT NULL,
    company VARCHAR(128) NULL,
    is_classify BOOLEAN NOT NULL DEFAULT FALSE,
    is_group BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_model_groups_site_key (site_id, group_key),
    KEY idx_llm_model_groups_catalog (site_id, status, sort_order),
    CONSTRAINT fk_llm_model_groups_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS llm_models (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    group_id BIGINT UNSIGNED NULL,
    parent_model_id BIGINT UNSIGNED NULL,
    model_key VARCHAR(128) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    company VARCHAR(128) NULL,
    icon_url VARCHAR(1024) NULL,
    description VARCHAR(1024) NULL,
    route VARCHAR(255) NULL,
    tags_json JSON NULL,
    is_free BOOLEAN NOT NULL DEFAULT FALSE,
    is_thinking BOOLEAN NOT NULL DEFAULT FALSE,
    only_points BOOLEAN NOT NULL DEFAULT FALSE,
    allow_points BOOLEAN NOT NULL DEFAULT TRUE,
    consume_points DECIMAL(20,6) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_models_site_key (site_id, model_key),
    KEY idx_llm_models_catalog (site_id, status, sort_order),
    CONSTRAINT fk_llm_models_site FOREIGN KEY (site_id) REFERENCES app_sites (id),
    CONSTRAINT fk_llm_models_group FOREIGN KEY (group_id) REFERENCES llm_model_groups (id),
    CONSTRAINT fk_llm_models_parent FOREIGN KEY (parent_model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agents (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    agent_key VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NULL,
    icon_url VARCHAR(1024) NULL,
    system_prompt LONGTEXT NULL,
    config_json JSON NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agents_site_key (site_id, agent_key),
    KEY idx_agents_catalog (site_id, status, sort_order),
    CONSTRAINT fk_agents_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS conversation_groups (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_conversation_groups_user_updated (user_id, updated_at),
    CONSTRAINT fk_conversation_groups_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    group_id BIGINT UNSIGNED NULL,
    model_id BIGINT UNSIGNED NULL,
    agent_id BIGINT UNSIGNED NULL,
    title VARCHAR(255) NULL,
    conversation_type VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    agent_key VARCHAR(128) NULL,
    system_prompt TEXT NULL,
    model_params_json JSON NULL,
    message_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_conversations_user_updated (user_id, updated_at),
    KEY idx_conversations_group_updated (group_id, updated_at),
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_conversations_group FOREIGN KEY (group_id) REFERENCES conversation_groups (id),
    CONSTRAINT fk_conversations_model FOREIGN KEY (model_id) REFERENCES llm_models (id),
    CONSTRAINT fk_conversations_agent FOREIGN KEY (agent_id) REFERENCES agents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS conversation_group_models (
    group_id BIGINT UNSIGNED NOT NULL,
    model_id BIGINT UNSIGNED NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (group_id, model_id),
    CONSTRAINT fk_group_models_group FOREIGN KEY (group_id) REFERENCES conversation_groups (id),
    CONSTRAINT fk_group_models_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
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
    UNIQUE KEY uk_chat_messages_sequence (conversation_id, sequence_no),
    KEY idx_chat_messages_conversation_created (conversation_id, created_at, id),
    CONSTRAINT fk_chat_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_chat_messages_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS message_feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    rating TINYINT NOT NULL,
    comment VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_feedback_user_message (message_id, user_id),
    KEY idx_message_feedback_user_created (user_id, created_at),
    CONSTRAINT fk_message_feedback_message FOREIGN KEY (message_id) REFERENCES chat_messages (id),
    CONSTRAINT fk_message_feedback_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_message_feedback_rating CHECK (rating IN (-1, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS uploaded_files (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    conversation_id BIGINT UNSIGNED NULL,
    message_id BIGINT UNSIGNED NULL,
    storage_key VARCHAR(1024) NOT NULL,
    original_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255) NULL,
    extension VARCHAR(32) NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    checksum_sha256 CHAR(64) NULL,
    extracted_text LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_uploaded_files_user_created (user_id, created_at),
    KEY idx_uploaded_files_conversation (conversation_id),
    CONSTRAINT fk_uploaded_files_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_uploaded_files_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_uploaded_files_message FOREIGN KEY (message_id) REFERENCES chat_messages (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS message_attachments (
    message_id BIGINT UNSIGNED NOT NULL,
    file_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (message_id, file_id),
    CONSTRAINT fk_message_attachments_message FOREIGN KEY (message_id) REFERENCES chat_messages (id),
    CONSTRAINT fk_message_attachments_file FOREIGN KEY (file_id) REFERENCES uploaded_files (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS points_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    balance DECIMAL(20,6) NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_accounts_user (user_id),
    CONSTRAINT fk_points_accounts_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_points_accounts_balance CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS points_ledger (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(20,6) NOT NULL,
    balance_after DECIMAL(20,6) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    reference_type VARCHAR(64) NULL,
    reference_id BIGINT UNSIGNED NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_ledger_idempotency (idempotency_key),
    KEY idx_points_ledger_user_created (user_id, created_at, id),
    KEY idx_points_ledger_reference (reference_type, reference_id),
    CONSTRAINT fk_points_ledger_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_points_ledger_account FOREIGN KEY (account_id) REFERENCES points_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chat_usage (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    conversation_id BIGINT UNSIGNED NULL,
    message_id BIGINT UNSIGNED NULL,
    model_id BIGINT UNSIGNED NULL,
    provider VARCHAR(64) NULL,
    request_id VARCHAR(128) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    points_consumed DECIMAL(20,6) NOT NULL DEFAULT 0,
    latency_ms INT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_usage_request (request_id),
    KEY idx_chat_usage_user_created (user_id, created_at),
    KEY idx_chat_usage_conversation (conversation_id),
    CONSTRAINT fk_chat_usage_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT fk_chat_usage_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_chat_usage_message FOREIGN KEY (message_id) REFERENCES chat_messages (id),
    CONSTRAINT fk_chat_usage_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS async_tasks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    provider_task_id VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    progress DECIMAL(5,2) NOT NULL DEFAULT 0,
    input_json JSON NULL,
    result_json JSON NULL,
    error_message VARCHAR(2048) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_async_tasks_user_created (user_id, created_at),
    KEY idx_async_tasks_status (status, updated_at),
    CONSTRAINT fk_async_tasks_user FOREIGN KEY (user_id) REFERENCES user_accounts (id),
    CONSTRAINT chk_async_tasks_progress CHECK (progress >= 0 AND progress <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_runs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_id BIGINT UNSIGNED NOT NULL,
    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    input_json JSON NULL,
    result_json JSON NULL,
    error_message VARCHAR(2048) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_agent_runs_user_created (user_id, created_at),
    KEY idx_agent_runs_conversation (conversation_id, created_at),
    CONSTRAINT fk_agent_runs_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT fk_agent_runs_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_agent_runs_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS conversation_shares (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    share_token CHAR(64) NOT NULL,
    title VARCHAR(255) NULL,
    expires_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_shares_token (share_token),
    KEY idx_conversation_shares_conversation (conversation_id),
    KEY idx_conversation_shares_user (user_id),
    CONSTRAINT fk_conversation_shares_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_conversation_shares_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO app_sites (site_key, display_name, status)
VALUES ('default', 'Default Site', 'ACTIVE')
ON DUPLICATE KEY UPDATE site_key = VALUES(site_key);

CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

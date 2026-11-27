-- AI Canvas workflows, assets, inspiration chat and generation tasks.
-- Frontend-owned documents stay in JSON text columns so their shapes can evolve independently
-- from the relational ownership and lifecycle columns.

CREATE TABLE IF NOT EXISTS ai_canvas_workflows (
    id VARCHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NULL,
    nodes_json LONGTEXT NOT NULL,
    groups_json LONGTEXT NOT NULL,
    viewport_json LONGTEXT NOT NULL,
    cover_key VARCHAR(64) NULL,
    cover_url VARCHAR(1024) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_canvas_workflows_user_status_updated (user_id, status, updated_at),
    KEY idx_ai_canvas_workflows_public_created (status, created_at),
    CONSTRAINT fk_ai_canvas_workflows_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_canvas_assets (
    asset_key VARCHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    storage_key VARCHAR(768) NOT NULL,
    name VARCHAR(512) NOT NULL,
    category VARCHAR(32) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    extension VARCHAR(32) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    prompt VARCHAR(4000) NULL,
    model VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (asset_key),
    UNIQUE KEY uk_ai_canvas_assets_storage_key (storage_key),
    KEY idx_ai_canvas_assets_user_status_created (user_id, status, created_at),
    KEY idx_ai_canvas_assets_user_category (user_id, category, status),
    CONSTRAINT fk_ai_canvas_assets_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_canvas_chat_sessions (
    id VARCHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(255) NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_canvas_chat_sessions_user_updated (user_id, status, updated_at),
    CONSTRAINT fk_ai_canvas_chat_sessions_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_canvas_chat_messages (
    id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    refs_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_canvas_chat_messages_sequence (session_id, sequence_no),
    KEY idx_ai_canvas_chat_messages_session_created (session_id, created_at, id),
    CONSTRAINT fk_ai_canvas_chat_messages_session
        FOREIGN KEY (session_id) REFERENCES ai_canvas_chat_sessions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_canvas_tasks (
    task_id VARCHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    provider_task_id VARCHAR(255) NULL,
    model_key VARCHAR(128) NULL,
    upstream_model VARCHAR(255) NULL,
    prompt LONGTEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    progress INT NOT NULL DEFAULT 0,
    result_json LONGTEXT NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id),
    KEY idx_ai_canvas_tasks_user_type_created (user_id, task_type, created_at),
    KEY idx_ai_canvas_tasks_status_updated (status, updated_at),
    CONSTRAINT fk_ai_canvas_tasks_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ai_canvas_workflows
    (id, user_id, title, description, nodes_json, groups_json, viewport_json, cover_key, cover_url, status)
SELECT
    'cat-demo',
    NULL,
    '橘猫的圣诞夜',
    'Workflow with 1 Image, 1 Text',
    '[{"id":"node-demo-text","type":"Text","title":"故事开场","x":80,"y":80,"prompt":"","status":"success","text":"一只橘猫在圣诞树前等待主人回家。","parentIds":[]},{"id":"node-demo-image","type":"Image","title":"圣诞橘猫","x":420,"y":80,"prompt":"一只橘猫坐在温暖的圣诞树旁，电影感光影，写实摄影","status":"idle","parentIds":["node-demo-text"]}]',
    '[]',
    '{"x":0,"y":0,"zoom":0.8}',
    NULL,
    '',
    'PUBLIC'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_canvas_workflows WHERE id = 'cat-demo'
);

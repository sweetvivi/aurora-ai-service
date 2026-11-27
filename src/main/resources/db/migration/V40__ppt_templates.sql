CREATE TABLE IF NOT EXISTS ppt_templates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(128) NOT NULL,
    description VARCHAR(2048) NULL,
    scenes_json LONGTEXT NULL,
    layouts_json LONGTEXT NULL,
    theme_json LONGTEXT NULL,
    source_storage_key VARCHAR(512) NULL,
    source_file_name VARCHAR(512) NULL,
    preview_url VARCHAR(1024) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ppt_templates_template_id (template_id),
    KEY idx_ppt_templates_status_category (status, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

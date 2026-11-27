-- Structured, per-key site settings.
--
-- The V1 `app_sites.config_json` column stays reserved for opaque site payloads. Queryable
-- runtime switches live here instead, so a single flag can be updated, audited and cached
-- independently without rewriting a JSON document. This migration is additive only.

CREATE TABLE IF NOT EXISTS app_site_settings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NOT NULL,
    setting_key VARCHAR(128) NOT NULL,
    setting_value VARCHAR(2048) NULL,
    value_type VARCHAR(16) NOT NULL DEFAULT 'STRING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_site_settings_site_key (site_id, setting_key),
    KEY idx_app_site_settings_site (site_id, setting_key),
    CONSTRAINT fk_app_site_settings_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Defaults for the local site. Feature flags use the `feature.` prefix; other keys are flat.
INSERT INTO app_site_settings (site_id, setting_key, setting_value, value_type)
SELECT s.id, seed.setting_key, seed.setting_value, seed.value_type
FROM app_sites s
JOIN (
    SELECT 'feature.chat' AS setting_key, 'true' AS setting_value, 'BOOLEAN' AS value_type
    UNION ALL SELECT 'feature.stream', 'true', 'BOOLEAN'
    UNION ALL SELECT 'feature.attachment', 'false', 'BOOLEAN'
    UNION ALL SELECT 'feature.share', 'true', 'BOOLEAN'
    UNION ALL SELECT 'feature.points', 'false', 'BOOLEAN'
    UNION ALL SELECT 'announcement', '欢迎使用 AI 对话服务', 'STRING'
    UNION ALL SELECT 'default_model', 'deepseek-v4.1-flash', 'STRING'
) seed
WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), value_type = VALUES(value_type);

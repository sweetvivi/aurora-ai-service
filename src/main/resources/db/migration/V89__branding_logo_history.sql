-- Audit trail for site chrome logo replacements (slot / old / new / time).

CREATE TABLE IF NOT EXISTS branding_logo_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT NOT NULL,
    slot_key VARCHAR(32) NOT NULL,
    old_url VARCHAR(2048) NULL,
    new_url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_branding_logo_history_site_time (site_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

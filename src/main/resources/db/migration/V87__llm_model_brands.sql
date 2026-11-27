-- Operational brand layer for model pickers (vendor icon / display / sort).
-- Models still belong via llm_models.company; brand_key matches the company slug
-- or an exact display_name match (needed for CJK company names).

CREATE TABLE IF NOT EXISTS llm_model_brands (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    brand_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    icon_url VARCHAR(1024) NULL,
    description VARCHAR(512) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_model_brands_key (brand_key),
    KEY idx_llm_model_brands_status_sort (status, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

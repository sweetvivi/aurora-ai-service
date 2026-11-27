-- Model tags as a real table.
--
-- `llm_models.tags_json` exists in V1 but is left unused: a JSON column cannot be mapped reliably
-- by Hibernate across MySQL and H2, and tags are a display field the catalogue wants to filter on.
-- Storing them relationally keeps the mapping portable and the column indexable.

CREATE TABLE IF NOT EXISTS llm_model_tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_id BIGINT UNSIGNED NOT NULL,
    tag VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_model_tags_model_tag (model_id, tag),
    KEY idx_llm_model_tags_model (model_id, sort_order),
    CONSTRAINT fk_llm_model_tags_model FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Development seed, mirroring what the seeded model names already imply. Operations curates the
-- real tags through the admin side once it exists.
INSERT INTO llm_model_tags (model_id, tag, sort_order)
SELECT m.id, seed.tag, seed.sort_order
FROM llm_models m
JOIN (
    SELECT 'deepseek-v4.1-flash' AS model_key, '思考' AS tag, 10 AS sort_order
    UNION ALL SELECT 'deepseek-v4.1-flash-0910', '思考', 10
    UNION ALL SELECT 'deepseek-v4-flash-0731', '快速', 20
    UNION ALL SELECT 'deepseek-v4-pro-0813', '高能力', 10
) seed ON seed.model_key = m.model_key
ON DUPLICATE KEY UPDATE sort_order = VALUES(sort_order);

-- Model-version fallback groups are a public catalogue abstraction. A member can point at any
-- model of the same type, even when it belongs to another channel or vendor.

CREATE TABLE IF NOT EXISTS llm_model_fallback_groups (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    group_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    model_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    order_customized BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_model_fallback_groups_site_key (site_id, group_key),
    KEY idx_llm_model_fallback_groups_public (site_id, status, model_type, sort_order),
    CONSTRAINT fk_llm_model_fallback_groups_site
        FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS llm_model_fallback_members (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    group_id BIGINT UNSIGNED NOT NULL,
    model_id BIGINT UNSIGNED NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_model_fallback_members_group_model (group_id, model_id),
    KEY idx_llm_model_fallback_members_order (group_id, enabled, priority),
    KEY idx_llm_model_fallback_members_model (model_id),
    CONSTRAINT fk_llm_model_fallback_members_group
        FOREIGN KEY (group_id) REFERENCES llm_model_fallback_groups (id) ON DELETE CASCADE,
    CONSTRAINT fk_llm_model_fallback_members_model
        FOREIGN KEY (model_id) REFERENCES llm_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO llm_model_fallback_groups
    (site_id, group_key, display_name, model_type, status, sort_order, order_customized)
SELECT NULL, 'gpt-image-2.0', 'GPT-Image-2.0', 'IMAGE', 'ACTIVE', 10, FALSE
WHERE NOT EXISTS (
    SELECT 1
    FROM llm_model_fallback_groups
    WHERE site_id IS NULL AND group_key = 'gpt-image-2.0'
);

INSERT INTO llm_model_fallback_members (group_id, model_id, priority, enabled)
SELECT grouped.id,
       ranked.id,
       ROW_NUMBER() OVER (
           ORDER BY COALESCE(
                        NULLIF(ranked.consume_points, 0),
                        COALESCE(ranked.input_points_per_1k, 0)
                            + COALESCE(ranked.output_points_per_1k, 0)
                    ),
                    ranked.sort_order,
                    ranked.id
       ) - 1,
       TRUE
FROM llm_model_fallback_groups grouped
JOIN (
    SELECT model.id,
           model.consume_points,
           model.input_points_per_1k,
           model.output_points_per_1k,
           model.sort_order
    FROM llm_models model
    WHERE model.model_type = 'IMAGE'
      AND model.status = 'ACTIVE'
      AND model.upstream_available = TRUE
      AND model.site_id IS NULL
      AND model.model_key IN (
          'gptimage2', 'gptimage2-cc', 'gpt-image-2', 'gpt-image-2-cc'
      )
) ranked ON TRUE
WHERE grouped.site_id IS NULL
  AND grouped.group_key = 'gpt-image-2.0'
  AND NOT EXISTS (
      SELECT 1
      FROM llm_model_fallback_members existing
      WHERE existing.group_id = grouped.id
        AND existing.model_id = ranked.id
  );

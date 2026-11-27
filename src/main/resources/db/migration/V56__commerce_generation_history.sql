-- Persist the user's e-commerce generation history separately from chat conversations.
-- The generated image itself remains in uploaded_files / blob storage.
CREATE TABLE IF NOT EXISTS commerce_generation_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    tool_key VARCHAR(128) NOT NULL,
    tool_label VARCHAR(255) NOT NULL,
    prompt VARCHAR(4000) NULL,
    result_url VARCHAR(1024) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_commerce_history_user_created (site_id, user_id, created_at, id),
    CONSTRAINT fk_commerce_history_site FOREIGN KEY (site_id) REFERENCES app_sites (id),
    CONSTRAINT fk_commerce_history_user FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

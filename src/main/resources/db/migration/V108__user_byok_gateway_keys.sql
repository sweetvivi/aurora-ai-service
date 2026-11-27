-- V108: 用户自带Aurora Key（按模块落库，AES-GCM 加密）。
-- 每个用户每个模块（chat / image / video）最多一把；明文不落库。

CREATE TABLE IF NOT EXISTS user_byok_gateway_keys (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    -- chat | image | video
    module VARCHAR(16) NOT NULL,
    key_ciphertext TINYBLOB NOT NULL,
    key_nonce VARBINARY(12) NOT NULL,
    key_tag VARBINARY(16) NOT NULL,
    -- 脱敏展示用，如 sk-zjFP…7f19
    key_hint VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_byok_module (user_id, module),
    KEY idx_user_byok_site (site_id),
    CONSTRAINT fk_user_byok_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

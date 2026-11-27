-- One New API relay token per user. The encrypted relay key is a credential, not a local balance:
-- quota ownership and accounting remain authoritative in New API.

CREATE TABLE IF NOT EXISTS user_gateway_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    newapi_token_id BIGINT UNSIGNED NULL,
    token_name VARCHAR(50) NOT NULL,
    key_ciphertext BLOB NULL,
    key_nonce BINARY(12) NULL,
    key_tag BINARY(16) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
    last_error VARCHAR(512) NULL,
    attempt INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    total_granted BIGINT NOT NULL DEFAULT 0,
    total_used BIGINT NOT NULL DEFAULT 0,
    total_available BIGINT NOT NULL DEFAULT 0,
    unlimited_quota BOOLEAN NOT NULL DEFAULT FALSE,
    model_limits TEXT NULL,
    expires_at DATETIME(6) NULL,
    last_sync_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_gateway_tokens_user (user_id),
    UNIQUE KEY uk_user_gateway_tokens_newapi_id (newapi_token_id),
    UNIQUE KEY uk_user_gateway_tokens_name (token_name),
    KEY idx_user_gateway_tokens_status_retry (status, next_retry_at),
    CONSTRAINT fk_user_gateway_tokens_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

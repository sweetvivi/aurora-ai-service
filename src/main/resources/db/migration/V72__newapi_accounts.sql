-- A platform account and its New API user now share one password: the credentials used to sign in
-- here are also the credentials accepted by the New API console. The gateway still has to sign in
-- *as* that user to manage the PAT and relay token, so the mirrored password is kept encrypted
-- (AES-256-GCM, NEW_API_ENCRYPTION_KEY) instead of being re-derivable.
--
-- This table is the authoritative local -> New API identity mapping. user_gateway_tokens keeps a
-- mirrored newapi_user_id because provisioning works on that row, but it is written from the value
-- resolved here.

CREATE TABLE IF NOT EXISTS newapi_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    newapi_user_id BIGINT UNSIGNED NULL,
    username VARCHAR(64) NOT NULL,
    password_ciphertext TINYBLOB NULL,
    password_nonce VARBINARY(12) NULL,
    password_tag VARBINARY(16) NULL,
    synced_at DATETIME(6) NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_newapi_accounts_user (user_id),
    UNIQUE KEY uk_newapi_accounts_newapi_user (newapi_user_id),
    CONSTRAINT fk_newapi_accounts_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

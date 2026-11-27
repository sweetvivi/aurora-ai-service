-- 每用户每分组一把 Relay Token，用到时才建。
--
-- New API 的分组写在 Token 的 `group` 上，一个 Token 只有一个分组；而模型可用的分组写在
-- `llm_models.upstream_groups` 里（V104）。所以「用户请求某分组的模型」必须换成该分组的 Token。
--
-- 上游 rc.47 虽然有 group=auto，但Aurora那套部署在请求入口直接 403（见 docs/new-api-billing.md
-- 5.1.1），且 auto_groups 上限为 5，覆盖不了实际的分组数量。因此这里按分组各存一把：
-- 首次用到某分组时用用户的 PAT 现建，密钥加密落库，之后直接复用。
--
-- `base_gateway_token_id` 指向 user_gateway_tokens：分组令牌用的是那把令牌所属账号的 PAT，
-- 基础令牌被清理时这些也应该跟着失效。

CREATE TABLE IF NOT EXISTS newapi_group_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    group_key VARCHAR(64) NOT NULL,
    token_name VARCHAR(50) NOT NULL,
    newapi_token_id BIGINT UNSIGNED NULL,
    key_ciphertext TINYBLOB NULL,
    key_nonce VARBINARY(12) NULL,
    key_tag VARBINARY(16) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
    last_error VARCHAR(512) NULL,
    attempt INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_newapi_group_tokens_user_group (user_id, group_key),
    UNIQUE KEY uk_newapi_group_tokens_newapi_id (newapi_token_id),
    UNIQUE KEY uk_newapi_group_tokens_name (token_name),
    KEY idx_newapi_group_tokens_status_retry (status, next_retry_at),
    CONSTRAINT fk_newapi_group_tokens_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

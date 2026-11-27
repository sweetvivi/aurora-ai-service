-- New API owns quota and billing. These columns only bind a platform account to one New API
-- identity/PAT/relay token; they are not a local balance or ledger.

ALTER TABLE user_gateway_tokens
    ADD COLUMN newapi_user_id BIGINT UNSIGNED NULL AFTER site_id,
    ADD COLUMN newapi_pat_id BIGINT UNSIGNED NULL AFTER newapi_token_id,
    ADD COLUMN pat_ciphertext TINYBLOB NULL AFTER newapi_pat_id,
    ADD COLUMN pat_nonce VARBINARY(12) NULL AFTER pat_ciphertext,
    ADD COLUMN pat_tag VARBINARY(16) NULL AFTER pat_nonce,
    ADD COLUMN last_usage_error VARCHAR(512) NULL AFTER last_error,
    ADD UNIQUE KEY uk_user_gateway_tokens_newapi_user (newapi_user_id),
    ADD UNIQUE KEY uk_user_gateway_tokens_newapi_pat (newapi_pat_id);

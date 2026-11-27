ALTER TABLE newapi_accounts
    DROP COLUMN password_ciphertext,
    DROP COLUMN password_nonce,
    DROP COLUMN password_tag,
    DROP COLUMN synced_at;

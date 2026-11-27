-- Admin user management keeps an optional internal note with each site account.

ALTER TABLE user_accounts
    ADD COLUMN remark VARCHAR(1024) NULL AFTER status;

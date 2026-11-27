-- 邮箱是否「经上游验证」。注册时由 New API 的邮箱验证码确认，才置 1；
-- 个人中心手填的邮箱一律为 0，因为本站不发码，无法自行证明归属。
--
-- 默认 0 是刻意的：本迁移之前创建的账号都没有经过邮箱验证，
-- 打开 app.auth.email-verification.login-requires-verified-email 之前必须先补数据。
ALTER TABLE user_accounts
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

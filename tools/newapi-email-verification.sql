-- New API 邮箱验证配置模板（SQLite / hub-test-new-api）
--
-- 用法见 docs/newapi-deployment-checklist.md §5.1：
--   1. 复制本文件，把 <...> 换成真实值（SMTPToken 是邮箱的「授权码」，不是登录密码）；
--   2. scp 到 NAS 的 /tmp；
--   3. docker run --rm -v hub-test_new-api-data:/data -v /tmp:/host alpine sh -c \
--        'apk add --no-cache sqlite >/dev/null 2>&1; sqlite3 /data/one-api.db < /host/<文件名>.sql'
--   4. docker restart hub-test-new-api
--
-- ⚠️ 填好真实值的版本不要提交进仓库。
-- ⚠️ 不要同时打开 TurnstileCheckEnabled：它会给 /api/verification 加人机校验，
--    而本站的发码请求是服务端发出的，没有挑战令牌，结果是谁都注册不了。

INSERT OR REPLACE INTO options (key, value) VALUES
    -- SMTP 465 + 隐式 TLS（QQ / 163 / 阿里云邮件推送都用这一组）
    ('SMTPServer',            '<smtp.example.com>'),
    ('SMTPPort',              '465'),
    ('SMTPSSLEnabled',        'true'),
    ('SMTPStartTLSEnabled',   'false'),
    -- 账号与发件人通常填同一个地址
    ('SMTPAccount',           '<no-reply@example.com>'),
    ('SMTPFrom',              '<no-reply@example.com>'),
    -- 邮箱设置里生成的客户端授权码，不是登录密码
    ('SMTPToken',             '<客户端授权码>'),
    -- 开关放在最后一行：确认上面都填对了再打开
    ('EmailVerificationEnabled', 'true');

-- 回滚：只关开关，SMTP 留着，下次直接再打开即可
-- INSERT OR REPLACE INTO options (key, value) VALUES ('EmailVerificationEnabled', 'false');

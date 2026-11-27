-- ---------------------------------------------------------------------------------------------
-- 网关令牌：把 New API 的 `model_limits_enabled` 开关一起缓存下来
--
-- 背景：`/api/usage/token/` 与 `/api/user/login` 共用 New API 的 critical 限流（20 次 / 20 分钟
-- 每 IP）。原先每次 AI 调用都要实时查一次用量来判断余额，几轮论文生成就把配额耗尽，New API
-- 开始回 429，前端表现为「模型调用失败」。
--
-- 修法是复用库里已有的用量快照（`last_sync_at` + 配额三列）。但用量响应里除了配额还有
-- `model_limits_enabled` 这个布尔值没有落库，缓存命中时无从还原，只能退回硬编码默认值。
-- 这里补一列把它一起缓存，语义与 New API 的响应保持一致。
--
-- 默认 FALSE 与 `NewApiManagementClient` 解析该字段时的缺省值一致；历史行由下一次同步刷新。
-- ---------------------------------------------------------------------------------------------

ALTER TABLE user_gateway_tokens
    ADD COLUMN model_limits_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER model_limits;

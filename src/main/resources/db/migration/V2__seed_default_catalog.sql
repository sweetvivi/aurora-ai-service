-- Pre-populates the local catalogue so the chat endpoints work on a freshly migrated database.
-- Additive only: this migration never drops or renames an existing object.
--
-- The upstream gateway in use is the OpenAI compatible relay configured through app.llm.base-url.
-- Model keys below match the models that gateway advertises on GET /v1/models.

INSERT INTO user_accounts (site_id, username, nickname, status)
SELECT s.id, 'demo', 'Demo User', 'ACTIVE'
FROM app_sites s
WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);

INSERT INTO llm_model_groups (site_id, group_key, label, company, status, sort_order)
SELECT s.id, 'demo-relay', '示例中转', 'Jiguang', 'ACTIVE', 0
FROM app_sites s
WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE label = VALUES(label), company = VALUES(company), status = VALUES(status);

INSERT INTO llm_models (site_id, group_id, model_key, model_name, company, route, description,
                        is_free, is_thinking, only_points, allow_points, consume_points, status, sort_order)
SELECT s.id, g.id, seed.model_key, seed.model_name, 'DeepSeek', seed.route, seed.description,
       seed.is_free, seed.is_thinking, 0, 1, seed.consume_points, 'ACTIVE', seed.sort_order
FROM app_sites s
JOIN llm_model_groups g ON g.site_id = s.id AND g.group_key = 'demo-relay'
JOIN (
    SELECT 'deepseek-v4.1-flash' AS model_key,
           'DeepSeek V4.1 Flash' AS model_name,
           'deepseek-v4.1-flash' AS route,
           '极速通用对话模型，支持推理链输出' AS description,
           0 AS is_free,
           1 AS is_thinking,
           0.010000 AS consume_points,
           1 AS sort_order
    UNION ALL SELECT 'deepseek-v4.1-flash-0910', 'DeepSeek V4.1 Flash (0910)', 'deepseek-v4.1-flash-0910',
                     'V4.1 Flash 的 0910 版本快照', 0, 1, 0.010000, 2
    UNION ALL SELECT 'deepseek-v4-flash-0731', 'DeepSeek V4 Flash (0731)', 'deepseek-v4-flash-0731',
                     'V4 Flash 的 0731 版本快照', 0, 1, 0.010000, 3
    UNION ALL SELECT 'deepseek-v4-pro-0813', 'DeepSeek V4 Pro (0813)', 'deepseek-v4-pro-0813',
                     'V4 Pro 高能力版本快照，适合复杂推理', 0, 1, 0.050000, 4
) seed
WHERE s.site_key = 'default'
ON DUPLICATE KEY UPDATE model_name = VALUES(model_name),
                        route = VALUES(route),
                        description = VALUES(description),
                        is_thinking = VALUES(is_thinking),
                        consume_points = VALUES(consume_points),
                        status = VALUES(status),
                        sort_order = VALUES(sort_order);

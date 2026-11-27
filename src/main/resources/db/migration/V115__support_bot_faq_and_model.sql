-- 客服知识补种 + support-bot 默认绑定站点可用 CHAT 模型，降低「配了但聊不动」。

-- ---------------------------------------------------------------------------
-- FAQ 种子（按 question 幂等，不覆盖运营已改文案）
-- ---------------------------------------------------------------------------
INSERT INTO support_faq (site_id, question, answer, keywords, enabled, sort_order)
SELECT site.id,
       '如何充值或查看余额？',
       '登录后进入「个人中心 / 账户」可查看余额与消费记录。如需充值，请按页面指引完成支付；到账异常可在客服挂件中转人工并附上订单号。',
       '充值,余额,账单,支付',
       1, 10
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_faq f
      WHERE f.site_id = site.id AND f.question = '如何充值或查看余额？'
  );

INSERT INTO support_faq (site_id, question, answer, keywords, enabled, sort_order)
SELECT site.id,
       '账号登录不上怎么办？',
       '请先确认账号、密码与验证码无误，并尝试清除浏览器缓存后重试。若仍无法登录，请转人工并留下注册邮箱/手机号，我们会协助核查。',
       '登录,密码,验证码,账号',
       1, 20
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_faq f
      WHERE f.site_id = site.id AND f.question = '账号登录不上怎么办？'
  );

INSERT INTO support_faq (site_id, question, answer, keywords, enabled, sort_order)
SELECT site.id,
       '生成失败或一直排队怎么处理？',
       '多数情况会自动重试。可刷新任务列表查看最新状态；若长时间无进度，请记录任务时间与所用模型后转人工，我们会排查上游与配额。',
       '失败,排队,超时,任务,生成',
       1, 30
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_faq f
      WHERE f.site_id = site.id AND f.question = '生成失败或一直排队怎么处理？'
  );

INSERT INTO support_faq (site_id, question, answer, keywords, enabled, sort_order)
SELECT site.id,
       '支持哪些模型和功能？',
       '模型与能力以站内「模型」目录及各功能页可选列表为准。不同模型在速度、价格与能力上有差异，具体以页面展示为准。',
       '模型,功能,能力,支持',
       1, 40
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_faq f
      WHERE f.site_id = site.id AND f.question = '支持哪些模型和功能？'
  );

INSERT INTO support_faq (site_id, question, answer, keywords, enabled, sort_order)
SELECT site.id,
       '如何申请退款？',
       '退款需人工审核。请在客服挂件提交「转人工」，说明订单号、金额与原因；符合规则的订单我们会尽快处理并回复。',
       '退款,退费,订单',
       1, 50
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM support_faq f
      WHERE f.site_id = site.id AND f.question = '如何申请退款？'
  );

-- ---------------------------------------------------------------------------
-- 为尚未绑定模型的 support-bot 挂上可用 CHAT 模型：
-- 优先站点 default_model，否则取该站点可见的第一个 ACTIVE CHAT 模型。
-- 注意：相关子查询里不要把外层列写进 JOIN ON（MySQL 会报 Unknown column）。
-- ---------------------------------------------------------------------------
INSERT INTO agent_models (agent_id, model_id, is_default, sort_order)
SELECT picked.agent_id, picked.model_id, 1, 0
FROM (
    SELECT a.id AS agent_id,
           COALESCE(
               (
                   SELECT m.id
                   FROM llm_models m
                   WHERE UPPER(m.model_type) = 'CHAT'
                     AND UPPER(m.status) = 'ACTIVE'
                     AND (m.site_id IS NULL OR m.site_id = a.site_id)
                     AND EXISTS (
                         SELECT 1
                         FROM app_site_settings setting
                         WHERE setting.site_id = a.site_id
                           AND setting.setting_key = 'default_model'
                           AND TRIM(IFNULL(setting.setting_value, '')) <> ''
                           AND m.model_key = TRIM(setting.setting_value)
                     )
                   ORDER BY m.id
                   LIMIT 1
               ),
               (
                   SELECT m.id
                   FROM llm_models m
                   WHERE UPPER(m.model_type) = 'CHAT'
                     AND UPPER(m.status) = 'ACTIVE'
                     AND (m.site_id IS NULL OR m.site_id = a.site_id)
                   ORDER BY m.id
                   LIMIT 1
               )
           ) AS model_id
    FROM agents a
    WHERE a.agent_key = 'support-bot'
      AND a.status = 'ACTIVE'
) picked
WHERE picked.model_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM agent_models am WHERE am.agent_id = picked.agent_id
  );

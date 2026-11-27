-- V45: add the generic entry point used by the AI education landing page.
-- It is intentionally an ordinary education agent: the landing page always sends
-- free-form requests here instead of guessing a card from the current list.
SET @education_site_id := (SELECT MIN(id) FROM app_sites);
SET @education_category_id := (
    SELECT id FROM agent_categories
    WHERE category_key = 'ai-education'
    ORDER BY id
    LIMIT 1
);

INSERT INTO agents (
    site_id, category_id, agent_key, sn, agent_type, ui_type, scope, classify, child_classify,
    chat_suggest, name, description, system_prompt, chat_prologue, visibility, publish_status,
    status, score, see_count, use_count, is_hot, sort_order, published_at, ui_config_json
)
SELECT @education_site_id, @education_category_id, 'education-learning-assistant',
       'education-learning-assistant', 'OFFICIAL', 1, 1, 'AI 教育', '通用助手', 1,
       '小小学习助手',
       '面向教师、家长和学生的通用 AI 教育助手，先澄清目标，再给出可执行的学习或教学支持。',
       '你是“小小学习助手”，一个通用的 AI 教育助手。你服务于学生、家长和教师。先判断用户是在学习、备课、沟通、规划还是需要材料分析，再用简洁的问题补齐最少必要信息；随后给出分步骤、可执行、适龄的建议或草稿。不要凭空编造教材、分数线、学校政策、题目答案或学生数据；涉及学生隐私时提醒脱敏；涉及心理、安全、医学或升学政策时说明边界并建议联系合适的专业人员。用户明确要求某个专门教育智能体时，可以建议其进入对应分类，但当前任务先尽量直接帮助完成。',
       '告诉我你想解决的学习、教学或家庭教育问题，我会先帮你梳理清楚。',
       'PUBLIC', 'PUBLISHED', 'ACTIVE', 6.00, 0, 0, 1, -10, CURRENT_TIMESTAMP,
       '{"enabled":true,"title":"描述你的教育需求","description":"可以直接说学习、备课、作业、沟通或计划方面的问题。","icon":"ri-graduation-cap-line","submitLabel":"开始咨询","fields":[{"key":"role","label":"你的身份","type":"choice","required":true,"options":["学生","教师","家长","其他"]},{"key":"goal","label":"想解决什么","type":"textarea","required":true,"placeholder":"例如：帮我为初二英语期末复习安排一周计划"},{"key":"context","label":"补充背景","type":"textarea","placeholder":"可选：年级、学科、时间、已有材料或限制条件"}]}'
WHERE @education_site_id IS NOT NULL
  AND @education_category_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM agents WHERE agent_key = 'education-learning-assistant'
  );

INSERT IGNORE INTO agent_tags (agent_id, tag, sort_order)
SELECT id, '通用助手', 0
FROM agents
WHERE agent_key = 'education-learning-assistant';

INSERT IGNORE INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT id, 'SUGGEST', '帮我把一个模糊的学习目标拆成今天能做的任务', 0
FROM agents
WHERE agent_key = 'education-learning-assistant';

INSERT IGNORE INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT id, 'SUGGEST', '我需要准备一节课，但还不知道从哪里开始', 1
FROM agents
WHERE agent_key = 'education-learning-assistant';

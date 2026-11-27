-- ---------------------------------------------------------------------------------------------
-- AI 领域目录：把 AI 教育与 AI 电商作为超级智能体中的领域分类。
--
-- 电商工具目录仍然保留在 commerce_tools /e-commerce；这里展示的是面向用户的智能体。
-- 一个智能体可以继续通过 linked_tool_key 绑定某个电商工具，但两套目录不混成一张卡。
-- ---------------------------------------------------------------------------------------------

INSERT INTO agent_categories (site_id, category_key, name, description, sort_order, status)
SELECT (SELECT MIN(id) FROM app_sites),
       'ai-education',
       'AI 教育',
       '升学备考、学习辅导、家庭教育与教师工作的智能助手。',
       72,
       'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_categories WHERE category_key = 'ai-education'
);

INSERT INTO agent_categories (site_id, category_key, name, description, sort_order, status)
SELECT (SELECT MIN(id) FROM app_sites),
       'ai-commerce',
       'AI 电商',
       '商品视觉、营销内容与电商经营场景的智能体。',
       74,
       'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_categories WHERE category_key = 'ai-commerce'
);

-- 已有的电商特化智能体纳入 AI 电商分组，独立电商工具页不受影响。
UPDATE agents a
JOIN agent_categories c ON c.category_key = 'ai-commerce'
SET a.category_id = c.id,
    a.classify = 'AI 电商'
WHERE a.linked_tool_key IS NOT NULL;

-- ------------------------------- AI 教育首批智能体 -------------------------------

INSERT INTO agents (site_id,
                    category_id,
                    agent_key,
                    sn,
                    agent_type,
                    ui_type,
                    scope,
                    classify,
                    chat_suggest,
                    name,
                    description,
                    system_prompt,
                    chat_prologue,
                    visibility,
                    publish_status,
                    status,
                    is_hot,
                    sort_order,
                    published_at)
SELECT (SELECT MIN(id) FROM app_sites),
       (SELECT id FROM agent_categories WHERE category_key = 'ai-education'),
       'education-homework-review',
       'education-homework-review',
       'OFFICIAL',
       1,
       1,
       'AI 教育',
       1,
       '作业批改助手',
       '按年级、学科和评分标准批改作业，指出问题并给出下一步练习建议。',
       '你是作业批改助手。先识别年级、学科和题目要求，再按“结论—错误定位—原因—改进建议”的顺序反馈。不要替学生直接完成整份作业；信息不足时只追问最关键的一项。',
       '把题目、作答内容和年级告诉我，我会帮你定位问题并给出改进建议。',
       'PUBLIC',
       'PUBLISHED',
       'ACTIVE',
       1,
       10,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE sn = 'education-homework-review');

INSERT INTO agents (site_id,
                    category_id,
                    agent_key,
                    sn,
                    agent_type,
                    ui_type,
                    scope,
                    classify,
                    chat_suggest,
                    name,
                    description,
                    system_prompt,
                    chat_prologue,
                    visibility,
                    publish_status,
                    status,
                    is_hot,
                    sort_order,
                    published_at)
SELECT (SELECT MIN(id) FROM app_sites),
       (SELECT id FROM agent_categories WHERE category_key = 'ai-education'),
       'education-quiz-maker',
       'education-quiz-maker',
       'OFFICIAL',
       1,
       1,
       'AI 教育',
       1,
       '试题生成助手',
       '根据学科、年级、知识点和难度生成练习题、答案与解析。',
       '你是试题生成助手。先确认学段、学科、知识点和题型；输出题目、参考答案和简洁解析。题目要有梯度，避免脱离用户给出的教材范围。',
       '告诉我年级、学科和要练习的知识点，我来生成一组题目。',
       'PUBLIC',
       'PUBLISHED',
       'ACTIVE',
       1,
       20,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE sn = 'education-quiz-maker');

INSERT INTO agents (site_id,
                    category_id,
                    agent_key,
                    sn,
                    agent_type,
                    ui_type,
                    scope,
                    classify,
                    chat_suggest,
                    name,
                    description,
                    system_prompt,
                    chat_prologue,
                    visibility,
                    publish_status,
                    status,
                    is_hot,
                    sort_order,
                    published_at)
SELECT (SELECT MIN(id) FROM app_sites),
       (SELECT id FROM agent_categories WHERE category_key = 'ai-education'),
       'education-lesson-planner',
       'education-lesson-planner',
       'OFFICIAL',
       1,
       1,
       'AI 教育',
       1,
       '教案设计助手',
       '围绕教学目标、课时和学生基础设计可执行的课堂方案。',
       '你是教案设计助手。输出教学目标、重点难点、课堂流程、互动活动、练习与课后作业；先根据学段和课时控制难度与篇幅。',
       '告诉我学科、年级、课题和课时，我会整理一份可直接使用的教案框架。',
       'PUBLIC',
       'PUBLISHED',
       'ACTIVE',
       0,
       30,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE sn = 'education-lesson-planner');

INSERT INTO agents (site_id,
                    category_id,
                    agent_key,
                    sn,
                    agent_type,
                    ui_type,
                    scope,
                    classify,
                    chat_suggest,
                    name,
                    description,
                    system_prompt,
                    chat_prologue,
                    visibility,
                    publish_status,
                    status,
                    is_hot,
                    sort_order,
                    published_at)
SELECT (SELECT MIN(id) FROM app_sites),
       (SELECT id FROM agent_categories WHERE category_key = 'ai-education'),
       'education-student-behavior-guide',
       'education-student-behavior-guide',
       'OFFICIAL',
       1,
       1,
       'AI 教育',
       1,
       '学生行为诊断师',
       '整理学生行为观察记录，帮助教师和家长区分现象、可能原因与可执行的支持方案。',
       '你是学生行为支持助手，不做医学或心理疾病诊断。先复述可观察事实，再给出多种可能解释与低风险的沟通、环境和跟踪建议；涉及安全风险时提醒寻求学校或专业机构帮助。保护学生隐私，不要求真实姓名。',
       '可以使用学生昵称或代号，描述发生场景、具体行为和你已经观察到的变化。',
       'PUBLIC',
       'PUBLISHED',
       'ACTIVE',
       0,
       40,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE sn = 'education-student-behavior-guide');

-- 每个教育智能体提供 3 条低门槛起始问题，仍然使用普通对话，不预设专用表单。
INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '帮我批改这道五年级语文阅读题，并指出失分原因', 0
FROM agents a
WHERE a.sn = 'education-homework-review'
  AND NOT EXISTS (SELECT 1 FROM agent_questions q WHERE q.agent_id = a.id AND q.content = '帮我批改这道五年级语文阅读题，并指出失分原因');

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '出一组初中数学一次函数基础练习，附答案和解析', 1
FROM agents a
WHERE a.sn = 'education-quiz-maker'
  AND NOT EXISTS (SELECT 1 FROM agent_questions q WHERE q.agent_id = a.id AND q.content = '出一组初中数学一次函数基础练习，附答案和解析');

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '设计一节 40 分钟的八年级物理实验课', 0
FROM agents a
WHERE a.sn = 'education-lesson-planner'
  AND NOT EXISTS (SELECT 1 FROM agent_questions q WHERE q.agent_id = a.id AND q.content = '设计一节 40 分钟的八年级物理实验课');

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '学生在小组活动中反复回避发言，我该先观察什么？', 0
FROM agents a
WHERE a.sn = 'education-student-behavior-guide'
  AND NOT EXISTS (SELECT 1 FROM agent_questions q WHERE q.agent_id = a.id AND q.content = '学生在小组活动中反复回避发言，我该先观察什么？');

-- ---------------------------------------------------------------------------------------------
-- V34: 超级智能体：一条官方智能体记录
--
-- 为什么是一条种子数据，而不是新建一张表：
--   这个仓库的「超级智能体」本来就是角色市场那一套（V9 照着 ai1foo 的前端结构复刻），
--   目录页上并排两张入口卡——「创建智能体」和「创建多智能体蜂群」。工具调度属于第二张卡
--   描述的场景（多个工具/智能体联动），而第一张卡是纯人格对话。
--   所以不需要新表：一条 OFFICIAL 智能体 + 显式授予的业务能力，就是「执行体」最轻的落地形态。
--   前端入口（快速开始 / 蜂群卡）在后续迭代里指向它，数据层不必再动。
--
-- 关键点：run_commerce_tool 默认是关闭的（AgentTool#enabledByDefault = false），只有在这里
-- 显式写进 agent_abilities 才对这一个智能体可见。其余 136 个角色智能体拿不到它。
--
-- 幂等：三条都带 NOT EXISTS，重复执行不会产生重复行。
-- ---------------------------------------------------------------------------------------------

INSERT INTO agents (site_id,
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
       'super-agent',
       'super-agent',
       'OFFICIAL',
       1,
       1,
       '业务与产品',
       1,
       '超级智能体',
       '一句话下需求，自动调度电商工具箱与生图能力，直接产出可用图片。',
       '你是「超级智能体」，一个能直接动手把活干完的助手，不是只会给建议的顾问。

工作方式：
1. 先判断用户要的是「成品」还是「信息」。要成品（改图、出图、换背景、试穿、换模特、做套图）时，直接调用工具，不要反问，不要先长篇解释。
2. 用户上传了图片并要求某种效果时，调用 run_commerce_tool，从工具清单里挑最贴近需求的那个 toolKey。最终提示词写在 prompt 里，指代参考图时用「第一张图 / 第二张图」，并说明把哪张图的什么应用到哪张图上。
3. 没有合适的电商工具、但用户想要一张全新的图时，调用 generate_image。
4. 工具产出的图片会自动展示给用户。你只需要用一两句话说明「做了什么、哪些部分保持不变、需要注意什么」，不要复述图片内容。
5. 缺少必要信息（比如要换背景却没给参考图）时，用一句话说清缺什么，不要罗列一堆选项让人挑。

不要做的事：不要编造生成结果；不要声称完成了工具没有返回的事；不要输出代码块。',
       '把需求说清楚，或者直接把图拖进来 —— 剩下的交给我。',
       'PUBLIC',
       'PUBLISHED',
       'ACTIVE',
       1,
       0,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM agents WHERE sn = 'super-agent');

-- 显式授权的两个能力：电商工具（业务能力，默认关闭）+ 生图（基础能力）
INSERT INTO agent_abilities (agent_id, ability_key, ability_name, sort_order)
SELECT a.id, 'run_commerce_tool', '电商图片工具', 0
FROM agents a
WHERE a.sn = 'super-agent'
  AND NOT EXISTS (SELECT 1
                  FROM agent_abilities b
                  WHERE b.agent_id = a.id
                    AND b.ability_key = 'run_commerce_tool');

INSERT INTO agent_abilities (agent_id, ability_key, ability_name, sort_order)
SELECT a.id, 'generate_image', '图片生成', 1
FROM agents a
WHERE a.sn = 'super-agent'
  AND NOT EXISTS (SELECT 1
                  FROM agent_abilities b
                  WHERE b.agent_id = a.id
                    AND b.ability_key = 'generate_image');

-- 三个起始问题：都指向「上传图 + 要成品」，让人一眼看出它跟角色智能体不一样
INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '把这张图的背景换成纯白摄影棚背景，商品保持不变', 0
FROM agents a
WHERE a.sn = 'super-agent'
  AND NOT EXISTS (SELECT 1
                  FROM agent_questions q
                  WHERE q.agent_id = a.id
                    AND q.content = '把这张图的背景换成纯白摄影棚背景，商品保持不变');

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '让第二张图里的模特穿上第一张图的衣服，姿势自然一点', 1
FROM agents a
WHERE a.sn = 'super-agent'
  AND NOT EXISTS (SELECT 1
                  FROM agent_questions q
                  WHERE q.agent_id = a.id
                    AND q.content = '让第二张图里的模特穿上第一张图的衣服，姿势自然一点');

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT a.id, 'SUGGEST', '把商品放到一个有生活感的场景里，风格要干净、不抢主体', 2
FROM agents a
WHERE a.sn = 'super-agent'
  AND NOT EXISTS (SELECT 1
                  FROM agent_questions q
                  WHERE q.agent_id = a.id
                    AND q.content = '把商品放到一个有生活感的场景里，风格要干净、不抢主体');

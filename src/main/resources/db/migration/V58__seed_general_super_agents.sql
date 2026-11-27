-- Add approachable, general-purpose chat templates across the Super Agent catalogue.
-- These agents have no required UI fields, upload panel, or commerce-tool binding.

CREATE TEMPORARY TABLE tmp_general_super_agents (
    category_key VARCHAR(128) NOT NULL,
    agent_key VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NOT NULL,
    system_prompt TEXT NOT NULL,
    chat_prologue VARCHAR(1024) NOT NULL,
    topic_tag VARCHAR(64) NOT NULL,
    question_1 VARCHAR(512) NOT NULL,
    question_2 VARCHAR(512) NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_general_super_agents
    (category_key, agent_key, name, description, system_prompt, chat_prologue,
     topic_tag, question_1, question_2, sort_order)
VALUES
    ('programming', 'template-programming-companion', '编程问题拆解助手', '解释代码概念、分析报错并协助把实现任务拆成清晰步骤。', '你是一个通用编程搭档。根据用户提供的语言、代码和目标，先澄清关键上下文，再用简洁步骤解释思路、指出风险并给出可验证的示例。不要假设未提供的框架或运行环境；信息不足时先说明假设。', '把代码、报错或想实现的目标发给我，我们一起拆解。', '通用编程', '帮我分析这段代码为什么会报错', '把这个功能需求拆成几个实现步骤', 1),
    ('cloud-devops', 'template-ops-companion', '系统运维问答助手', '协助理解部署、日志、网络和基础设施问题，给出可回滚的排查步骤。', '你是一个通用运维与云架构助手。先识别环境、影响范围和最近变更，再按低风险优先的顺序提供检查命令、预期结果和回滚建议。涉及生产变更时明确风险与备份要求，不把猜测说成事实。', '描述服务、环境和现象，我会从安全的排查步骤开始。', '通用运维', '服务突然变慢，我应该先检查什么？', '帮我审阅一个部署方案的风险点', 1),
    ('data-ai', 'template-data-thinking', '数据分析思路助手', '帮助梳理数据问题、指标口径、分析路径和结果表达。', '你是一个通用数据分析助手。先确认分析目标、数据粒度和指标定义，再提出可执行的分析步骤与验证方法。没有实际数据时只提供方法或示例，不虚构统计结果；注意区分相关关系与因果关系。', '告诉我想回答的问题和手头的数据类型。', '数据分析', '我有一份销售表，适合从哪些角度分析？', '帮我检查这个指标口径是否清楚', 1),
    ('business-product', 'template-product-thinking', '产品与业务梳理助手', '把模糊想法整理成用户问题、目标、方案和验证指标。', '你是一个通用产品与业务搭档。帮助用户澄清目标用户、实际问题、约束和成功标准，再整理可比较的方案、优先级与验证步骤。不要未经依据承诺商业结果；遇到不确定信息时标出假设。', '说说你正在处理的产品或业务问题。', '产品梳理', '帮我把这个想法整理成一页需求说明', '如何为这个功能设计最小验证方案？', 1),
    ('security-quality', 'template-quality-review', '质量与安全检查助手', '从边界条件、失败路径、隐私和维护成本角度检查方案。', '你是一个通用质量与安全审阅助手。基于用户提供的代码、流程或方案，按影响和可能性列出具体风险，并给出可验证的修正建议。区分确定问题和待确认项，不声称完成了未实际执行的测试。', '贴上需要检查的代码、流程或设计，我会按风险排序反馈。', '质量检查', '请帮我检查这个接口设计的边界情况', '这段实现还需要补哪些测试？', 1),
    ('mobile-game', 'template-app-development', '应用开发搭档', '协助规划移动应用与游戏功能，分析交互、实现步骤和测试思路。', '你是一个通用移动应用与游戏开发搭档。根据目标平台、引擎或技术栈提供实现思路、交互建议和测试清单；未指定技术时先给平台无关方案，不假设项目依赖。', '告诉我想做的应用或游戏功能，以及目标平台。', '应用开发', '帮我设计一个设置页面的交互结构', '这个游戏机制需要考虑哪些边界情况？', 1),
    ('chinese-agents', 'template-chinese-workbench', '中文工作助手', '协助整理资料、撰写内容、制定计划和推进日常任务。', '你是一个通用中文工作助手。根据用户目标选择合适的协助方式：解释、归纳、起草、比较或拆解计划。保持表达清楚、具体；缺少关键信息时提出少量问题，也可以先给出明确标注假设的初稿。', '直接告诉我你想完成什么，我会选择合适的方式协助。', '通用助手', '帮我把这段材料整理成简洁摘要', '把这个目标拆成一周内可执行的计划', 1),
    ('ai-commerce', 'template-commerce-consultant', '电商经营问答助手', '围绕商品信息、店铺运营和内容规划提供通用讨论与清单。', '你是一个通用电商经营助手。根据用户提供的商品、渠道和经营目标，协助梳理商品表达、运营流程、内容方向和待验证假设。除非用户明确要求且相关工具可用，不要声称已生成图片、读取店铺数据或执行外部操作。', '描述商品、销售渠道或当前经营问题即可开始。', '电商经营', '帮我梳理上新前需要准备的商品信息', '为一款日常用品列出内容选题方向', 1),
    ('director-personas', 'template-film-story-consultant', '影视创作顾问', '从主题、人物、场景和节奏角度讨论影视创作方案。', '你是一个影视创作顾问，不模仿特定在世创作者的独特风格。围绕用户给出的题材和目标，提供主题提炼、人物动机、场景推进与节奏建议；可以提出多个方向供用户选择，不把创作偏好说成唯一答案。', '说说故事类型、核心想法或卡住的段落。', '影视创作', '帮我把一个简单想法扩展成故事梗概', '这场戏怎样写得更有张力？', 1),
    ('writer-personas', 'template-writing-companion', '写作灵感伙伴', '帮助启动写作、理清结构并改进表达，同时保留作者自己的声音。', '你是一个通用写作伙伴。先理解文体、读者和写作目的，再帮助用户构思、搭建结构或修改表达。尽量保留作者原有语气；没有明确要求时先给建议或局部示例，不直接替用户完成整篇作品。', '告诉我写作主题、读者和目前卡住的地方。', '写作', '给我三个不同角度的文章切入点', '帮我检查这段文字的结构和表达', 1),
    ('media-personas', 'template-content-planner', '内容选题策划助手', '把受众兴趣、内容目标和素材整理成可测试的选题方向。', '你是一个通用内容策划助手。根据目标受众、平台和内容目的提出具体选题、核心观点与可执行的大纲。不要伪造热点、数据或平台规则；无法确认的趋势应标为待核实，并优先提供常青选题。', '告诉我受众、平台和内容目标，我们一起找选题。', '内容策划', '为新手用户列出一组常青选题', '把这个选题整理成短视频大纲', 1),
    ('tianya-personas', 'template-open-discussion', '开放话题讨论助手', '从多个立场分析日常问题，整理事实、观点和可继续讨论的问题。', '你是一个理性、友善的开放话题讨论助手。先区分已知事实、个人判断与价值取舍，再呈现不同立场的理由和局限。避免人身攻击、阴谋论式断言和未经证实的信息；结尾可提出有助于继续讨论的问题。', '抛出一个你想讨论的问题或观点即可。', '话题讨论', '同一件事为什么会有完全不同的看法？', '帮我分析这个观点成立的条件和局限', 1);

UPDATE agent_categories
SET sort_order = 130
WHERE category_key = 'super-ai-education';

INSERT INTO agents (
    site_id, category_id, owner_user_id, agent_key, sn, agent_type, ui_type, scope,
    classify, child_classify, chat_suggest, name, description, system_prompt, chat_prologue,
    source_path, ui_config_json, visibility, publish_status, score, see_count, use_count,
    favorite_count, is_hot, published_at, status, sort_order
)
SELECT site.id, category.id, NULL, template.agent_key, template.agent_key, 'OFFICIAL', 1, 1,
       category.name, NULL, 1, template.name, template.description, template.system_prompt,
       template.chat_prologue, '内置通用模板', NULL, 'PUBLIC', 'PUBLISHED', 5.00, 0, 0,
       0, 0, CURRENT_TIMESTAMP(6), 'ACTIVE', template.sort_order
FROM tmp_general_super_agents template
JOIN app_sites site ON site.site_key = 'default'
JOIN agent_categories category
  ON category.site_id = site.id
 AND category.category_key = template.category_key
WHERE NOT EXISTS (
    SELECT 1 FROM agents existing
    WHERE existing.site_id = site.id
      AND existing.agent_key = template.agent_key
);

INSERT IGNORE INTO agent_tags (agent_id, tag, sort_order)
SELECT agent.id, template.topic_tag, 0
FROM tmp_general_super_agents template
JOIN agents agent ON agent.agent_key = template.agent_key
JOIN app_sites site ON site.id = agent.site_id AND site.site_key = 'default';

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT agent.id, 'SUGGEST', template.question_1, 0
FROM tmp_general_super_agents template
JOIN agents agent ON agent.agent_key = template.agent_key
JOIN app_sites site ON site.id = agent.site_id AND site.site_key = 'default'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_questions question
    WHERE question.agent_id = agent.id AND question.content = template.question_1
);

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT agent.id, 'SUGGEST', template.question_2, 1
FROM tmp_general_super_agents template
JOIN agents agent ON agent.agent_key = template.agent_key
JOIN app_sites site ON site.id = agent.site_id AND site.site_key = 'default'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_questions question
    WHERE question.agent_id = agent.id AND question.content = template.question_2
);

DROP TEMPORARY TABLE tmp_general_super_agents;

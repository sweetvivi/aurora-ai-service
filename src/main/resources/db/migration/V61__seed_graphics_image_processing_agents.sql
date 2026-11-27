-- Add a general-purpose graphics and image-processing category to Super Agent.
-- The four entries are ordinary chat agents: no required fields or tool bindings.

INSERT INTO agent_categories (
    site_id, category_key, name, icon_url, description, sort_order, status
)
SELECT site.id, 'graphics-image-processing', '图形与图像处理', NULL,
       '图像内容理解、图表解读、视觉创意与图片编辑思路。', 120, 'ACTIVE'
FROM app_sites site
WHERE site.site_key = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM agent_categories existing
      WHERE existing.site_id = site.id
        AND existing.category_key = 'graphics-image-processing'
  );

CREATE TEMPORARY TABLE tmp_graphics_image_agents (
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

INSERT INTO tmp_graphics_image_agents
    (agent_key, name, description, system_prompt, chat_prologue,
     topic_tag, question_1, question_2, sort_order)
VALUES
    ('template-image-understanding', '图像内容解读助手',
     '解读图像中的主体、场景、布局与视觉信息，整理观察结果和后续处理建议。',
     '你是一个通用图像内容解读助手。用户提供图片时，基于实际可见内容客观描述主体、场景、布局、文字和视觉关系；区分直接观察与推测，不臆造不可见细节。若当前模型无法读取图片，明确请用户描述图像或换用支持视觉输入的模型。用户未上传图片时，可先询问目标并给出分析框架。',
     '上传或描述一张图像，并告诉我你想了解什么。', '图像理解',
     '请描述这张图片的主体、构图和主要细节', '帮我从这张图中整理可见的信息和不确定点', 10),
    ('template-chart-interpreter', '图表解读助手',
     '帮助理解图表结构、指标与趋势；可依据上传图像或用户提供的数据分析。',
     '你是一个谨慎的图表解读助手。先辨认图表类型、坐标轴、单位、图例和数据范围，再总结趋势、对比与异常；区分图表直接支持的结论和进一步推断，不虚构读不清的数据。图片不可读时请用户提供清晰图像或原始数据。',
     '发来图表或数据，并说明你最想弄清的问题。', '图表解读',
     '帮我读出这张图表主要表达的趋势', '这组数据能支持哪些结论，哪些还不能下结论？', 20),
    ('template-visual-creative-planner', '视觉创意策划助手',
     '围绕用途、受众和风格整理视觉创意方向、构图方案与可复用的生成提示词。',
     '你是一个通用视觉创意策划助手。根据用户的用途、受众、主题和媒介，提出清晰且彼此有区别的视觉方向，描述主体、构图、色彩、光线、材质和文字留白，并可整理成适用于图像生成模型的提示词。不要声称已经生成或编辑了图片；不确定的品牌规范应向用户确认。',
     '告诉我画面用途、主题和偏好的风格，我来整理创意方向。', '视觉创意',
     '为一个新产品发布页构思三种视觉方向', '把这个画面想法整理成一段清晰的生图提示词', 30),
    ('template-image-editing-planner', '图片编辑方案助手',
     '分析图片调整目标，提供裁切、清理、换背景、色彩和细节优化的编辑步骤或提示词。',
     '你是一个通用图片编辑方案助手。先确认用户希望保留和改变的内容，再将图片编辑目标拆成明确步骤，涵盖主体保护、局部修改、构图、背景、色彩和细节。可为外部图片编辑工具撰写具体提示词，但除非当前会话确实调用了相应工具并收到结果，否则不可声称已修改或导出图片。',
     '描述要调整的图片内容、期望效果以及必须保留的部分。', '图片编辑',
     '帮我写一段只替换背景、保留商品细节的编辑提示词', '这张图显得杂乱，建议按什么顺序处理？', 40);

INSERT INTO agents (
    site_id, category_id, owner_user_id, agent_key, sn, agent_type, ui_type, scope,
    classify, child_classify, chat_suggest, name, description, system_prompt, chat_prologue,
    source_path, ui_config_json, visibility, publish_status, score, see_count, use_count,
    favorite_count, is_hot, published_at, status, sort_order
)
SELECT site.id, category.id, NULL, template.agent_key, template.agent_key,
       'OFFICIAL', 1, 1, category.name, NULL, 1, template.name,
       template.description, template.system_prompt, template.chat_prologue,
       '内置通用模板', NULL, 'PUBLIC', 'PUBLISHED', 5.00, 0, 0,
       0, 0, CURRENT_TIMESTAMP(6), 'ACTIVE', template.sort_order
FROM tmp_graphics_image_agents template
JOIN app_sites site ON site.site_key = 'default'
JOIN agent_categories category
  ON category.site_id = site.id
 AND category.category_key = 'graphics-image-processing'
WHERE NOT EXISTS (
    SELECT 1 FROM agents existing
    WHERE existing.site_id = site.id
      AND existing.agent_key = template.agent_key
);

INSERT IGNORE INTO agent_tags (agent_id, tag, sort_order)
SELECT agent.id, template.topic_tag, 0
FROM tmp_graphics_image_agents template
JOIN agents agent ON agent.agent_key = template.agent_key
JOIN app_sites site ON site.id = agent.site_id AND site.site_key = 'default';

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT agent.id, 'SUGGEST', template.question_1, 0
FROM tmp_graphics_image_agents template
JOIN agents agent ON agent.agent_key = template.agent_key
JOIN app_sites site ON site.id = agent.site_id AND site.site_key = 'default'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_questions existing
    WHERE existing.agent_id = agent.id
      AND existing.question_type = 'SUGGEST'
      AND existing.content = template.question_1
);

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT agent.id, 'SUGGEST', template.question_2, 1
FROM tmp_graphics_image_agents template
JOIN agents agent ON agent.agent_key = template.agent_key
JOIN app_sites site ON site.id = agent.site_id AND site.site_key = 'default'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_questions existing
    WHERE existing.agent_id = agent.id
      AND existing.question_type = 'SUGGEST'
      AND existing.content = template.question_2
);

DROP TEMPORARY TABLE tmp_graphics_image_agents;

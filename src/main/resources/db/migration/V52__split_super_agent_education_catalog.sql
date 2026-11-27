-- V48: AI Education owns its domain catalogue. Super Agent gets independent copies.
--
-- Both catalogues still use the shared agent runtime, conversations and model bindings,
-- but they no longer point at the same agent rows. This lets product copy, prompts and
-- panel schemas evolve independently in the two workspaces.

INSERT INTO agent_categories (site_id, category_key, name, icon_url, description, sort_order, status)
SELECT source.site_id,
       'super-ai-education',
       'AI 教育',
       source.icon_url,
       '超级智能体中的教育助手，可直接对话，也可使用任务参数快速开始。',
       source.sort_order,
       'ACTIVE'
FROM agent_categories source
WHERE source.category_key = 'ai-education'
  AND NOT EXISTS (
      SELECT 1
      FROM agent_categories target
      WHERE target.category_key = 'super-ai-education'
        AND target.site_id <=> source.site_id
  );

INSERT INTO agents (
    site_id, category_id, owner_user_id, agent_key, sn, agent_type, ui_type, scope,
    classify, child_classify, linked_tool_key, chat_suggest, ui_bg, name, description,
    icon_url, system_prompt, chat_prologue, source_model, source_path, ui_config_json,
    visibility, publish_status, score, see_count, use_count, favorite_count, is_hot,
    published_at, config_json, status, sort_order
)
SELECT source.site_id,
       target_category.id,
       NULL,
       CONCAT('super-', source.agent_key),
       CONCAT('super-', source.sn),
       'OFFICIAL',
       source.ui_type,
       1,
       'AI 教育',
       source.child_classify,
       source.linked_tool_key,
       source.chat_suggest,
       source.ui_bg,
       source.name,
       source.description,
       source.icon_url,
       source.system_prompt,
       source.chat_prologue,
       source.source_model,
       source.source_path,
       source.ui_config_json,
       'PUBLIC',
       'PUBLISHED',
       source.score,
       0,
       0,
       0,
       source.is_hot,
       CURRENT_TIMESTAMP(6),
       source.config_json,
       'ACTIVE',
       source.sort_order
FROM agents source
JOIN agent_categories source_category
  ON source_category.id = source.category_id
 AND source_category.category_key = 'ai-education'
JOIN agent_categories target_category
  ON target_category.category_key = 'super-ai-education'
 AND target_category.site_id <=> source.site_id
WHERE source.owner_user_id IS NULL
  AND source.agent_type = 'OFFICIAL'
  AND source.status = 'ACTIVE'
  AND source.visibility = 'PUBLIC'
  AND source.publish_status = 'PUBLISHED'
  AND NOT EXISTS (
      SELECT 1
      FROM agents existing
      WHERE existing.site_id <=> source.site_id
        AND existing.agent_key = CONCAT('super-', source.agent_key)
  );

INSERT IGNORE INTO agent_tags (agent_id, tag, sort_order)
SELECT target.id, relation.tag, relation.sort_order
FROM agents source
JOIN agent_categories source_category
  ON source_category.id = source.category_id
 AND source_category.category_key = 'ai-education'
JOIN agents target
  ON target.site_id <=> source.site_id
 AND target.agent_key = CONCAT('super-', source.agent_key)
JOIN agent_tags relation ON relation.agent_id = source.id;

INSERT INTO agent_questions (agent_id, question_type, content, sort_order)
SELECT target.id, relation.question_type, relation.content, relation.sort_order
FROM agents source
JOIN agent_categories source_category
  ON source_category.id = source.category_id
 AND source_category.category_key = 'ai-education'
JOIN agents target
  ON target.site_id <=> source.site_id
 AND target.agent_key = CONCAT('super-', source.agent_key)
JOIN agent_questions relation ON relation.agent_id = source.id
WHERE NOT EXISTS (
    SELECT 1
    FROM agent_questions existing
    WHERE existing.agent_id = target.id
      AND existing.question_type = relation.question_type
      AND existing.content = relation.content
);

INSERT IGNORE INTO agent_abilities (agent_id, ability_key, ability_name, config_json, sort_order)
SELECT target.id, relation.ability_key, relation.ability_name, relation.config_json, relation.sort_order
FROM agents source
JOIN agent_categories source_category
  ON source_category.id = source.category_id
 AND source_category.category_key = 'ai-education'
JOIN agents target
  ON target.site_id <=> source.site_id
 AND target.agent_key = CONCAT('super-', source.agent_key)
JOIN agent_abilities relation ON relation.agent_id = source.id;

INSERT IGNORE INTO agent_models (agent_id, model_id, is_default, sort_order)
SELECT target.id, relation.model_id, relation.is_default, relation.sort_order
FROM agents source
JOIN agent_categories source_category
  ON source_category.id = source.category_id
 AND source_category.category_key = 'ai-education'
JOIN agents target
  ON target.site_id <=> source.site_id
 AND target.agent_key = CONCAT('super-', source.agent_key)
JOIN agent_models relation ON relation.agent_id = source.id;

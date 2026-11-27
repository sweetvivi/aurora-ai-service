-- V49: expose every active commerce tool as a Super Agent entry.
-- The wrapper stores catalogue copy only. linked_tool_key keeps form schema and compose
-- execution owned by commerce_tools, so there is still one source of truth for the tool.

INSERT INTO agents (
    site_id, category_id, owner_user_id, agent_key, sn, agent_type, ui_type, scope,
    classify, child_classify, linked_tool_key, chat_suggest, name, description, icon_url,
    system_prompt, chat_prologue, source_path, visibility, publish_status, score, see_count,
    use_count, favorite_count, is_hot, published_at, status, sort_order
)
SELECT tool.site_id,
       category.id,
       NULL,
       CONCAT('commerce-tool-', tool.tool_key),
       CONCAT('commerce-tool-', tool.tool_key),
       'OFFICIAL',
       1,
       1,
       'AI 电商',
       tool.category,
       tool.tool_key,
       0,
       tool.name,
       tool.description,
       tool.icon_url,
       COALESCE(NULLIF(tool.prompt, ''), CONCAT('你是', tool.name, '。根据用户上传的素材与参数完成任务，并简洁说明生成结果。')),
       CONCAT('填写左侧参数并上传所需素材，我会帮你完成“', tool.name, '”。'),
       CONCAT('/e-commerce?tool=', tool.tool_key),
       'PUBLIC',
       'PUBLISHED',
       5.00,
       0,
       0,
       0,
       0,
       CURRENT_TIMESTAMP(6),
       'ACTIVE',
       tool.sort_order
FROM commerce_tools tool
JOIN agent_categories category
  ON category.category_key = 'ai-commerce'
 AND category.site_id <=> tool.site_id
WHERE tool.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1
      FROM agents existing
      WHERE existing.site_id <=> tool.site_id
        AND existing.linked_tool_key = tool.tool_key
        AND existing.status = 'ACTIVE'
  );

INSERT IGNORE INTO agent_tags (agent_id, tag, sort_order)
SELECT agent.id, '电商工具', 0
FROM agents agent
JOIN agent_categories category
  ON category.id = agent.category_id
 AND category.category_key = 'ai-commerce'
WHERE agent.linked_tool_key IS NOT NULL;

INSERT IGNORE INTO agent_tags (agent_id, tag, sort_order)
SELECT agent.id, tool.category, 1
FROM agents agent
JOIN agent_categories category
  ON category.id = agent.category_id
 AND category.category_key = 'ai-commerce'
JOIN commerce_tools tool
  ON tool.site_id <=> agent.site_id
 AND tool.tool_key = agent.linked_tool_key
WHERE agent.linked_tool_key IS NOT NULL;

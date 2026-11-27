-- Keep the education-specific copies discoverable without letting them dominate
-- the cross-category "all" catalogue ahead of general-purpose agents.
UPDATE agents agent
JOIN agent_categories category
  ON category.id = agent.category_id
SET agent.sort_order = 10000
WHERE category.category_key = 'super-ai-education'
  AND agent.sort_order < 10000;

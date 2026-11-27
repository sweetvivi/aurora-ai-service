-- V59 temporarily demoted the education copies in the all-agents catalogue.
-- Restore the sort order they inherited from their independent education sources.
UPDATE agents copy_agent
JOIN agent_categories copy_category
  ON copy_category.id = copy_agent.category_id
 AND copy_category.category_key = 'super-ai-education'
JOIN agents source_agent
  ON source_agent.site_id <=> copy_agent.site_id
 AND copy_agent.agent_key = CONCAT('super-', source_agent.agent_key)
JOIN agent_categories source_category
  ON source_category.id = source_agent.category_id
 AND source_category.category_key = 'ai-education'
SET copy_agent.sort_order = source_agent.sort_order;

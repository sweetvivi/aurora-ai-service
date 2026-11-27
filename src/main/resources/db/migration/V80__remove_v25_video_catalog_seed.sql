-- V80: remove the V25 placeholder video catalogue.
--
-- V25 inserted a synthetic provider (`video`) and four demo VIDEO models so the studio page
-- could render before New API sync existed. Catalogue ownership has since moved to New API
-- sync + admin presentation edits; keeping those rows mixed real models with unusable stubs.
--
-- Scope is intentionally narrow:
--   * only the four keys V25 wrote
--   * only when they still sit on provider_key = 'video'
-- Models with the same key that arrived later via New API (different provider) are left alone.
-- The synthetic provider is dropped only when it has no remaining models.

-- Dependent rows without ON DELETE CASCADE.
DELETE t
FROM llm_model_tags t
INNER JOIN llm_models m ON m.id = t.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

DELETE am
FROM agent_models am
INNER JOIN llm_models m ON m.id = am.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

UPDATE agent_conversations ac
INNER JOIN llm_models m ON m.id = ac.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
SET ac.model_id = NULL
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

UPDATE agent_chat_messages acm
INNER JOIN llm_models m ON m.id = acm.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
SET acm.model_id = NULL
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

UPDATE llm_models child
INNER JOIN llm_models parent ON parent.id = child.parent_model_id
INNER JOIN llm_providers p ON p.id = parent.provider_id
SET child.parent_model_id = NULL
WHERE p.provider_key = 'video'
  AND parent.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

-- llm_model_fallback_members cascades on model delete (V69).
DELETE m
FROM llm_models m
INNER JOIN llm_providers p ON p.id = m.provider_id
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

DELETE p
FROM llm_providers p
WHERE p.provider_key = 'video'
  AND NOT EXISTS (
      SELECT 1 FROM llm_models m WHERE m.provider_id = p.id
  );

-- V84: finish the V80 video-catalogue cleanup.
--
-- V80 deletes the four V25 demo VIDEO models, but it only clears five of the ten foreign keys
-- that point at `llm_models`. On an environment where those models were never referenced it
-- passes; where a conversation had picked one, InnoDB refuses the delete with
-- `fk_conversations_model` and the migration fails, which then blocks startup entirely.
--
-- This migration redoes the cleanup with every referencing table handled. It is idempotent: on
-- an environment where V80 already succeeded there is nothing left to match, so every statement
-- below affects zero rows.

-- Some referencing columns are NOT NULL, so those rows cannot keep a dangling model and have to
-- go instead of being nulled. Both are pure join rows: nothing but the pairing is lost.
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

DELETE gm
FROM conversation_group_models gm
INNER JOIN llm_models m ON m.id = gm.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

-- `llm_model_fallback_members` cascades on model delete (V69), but only when the FK was
-- recreated by that migration; leave nothing to chance on older schemas.
DELETE fm
FROM llm_model_fallback_members fm
INNER JOIN llm_models m ON m.id = fm.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

-- Nullable columns keep their row: the conversation / message / usage line is still meaningful
-- history, it simply no longer points at a model that is about to disappear.
UPDATE conversations c
INNER JOIN llm_models m ON m.id = c.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
SET c.model_id = NULL
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

UPDATE chat_messages cm
INNER JOIN llm_models m ON m.id = cm.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
SET cm.model_id = NULL
WHERE p.provider_key = 'video'
  AND m.model_key IN ('seedance-2.0', 'kling-2.5', 'wan-2.5', 'hailuo-02');

UPDATE chat_usage cu
INNER JOIN llm_models m ON m.id = cu.model_id
INNER JOIN llm_providers p ON p.id = m.provider_id
SET cu.model_id = NULL
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

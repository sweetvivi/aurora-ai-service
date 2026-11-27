-- Native upstream web search (OpenAI-style tools:[{type:"web_search"}]).
-- Local catalogue gate; not synced from New API. Default off until ops enables per model.
ALTER TABLE llm_models
    ADD COLUMN supports_web_search TINYINT(1) NOT NULL DEFAULT 0 AFTER supports_tools;

-- Enable for GPT-family keys verified on example.com (ops can still toggle in admin).
UPDATE llm_models
SET supports_web_search = 1
WHERE model_type = 'CHAT'
  AND (
        model_key LIKE 'gpt-%'
     OR route LIKE 'gpt-%'
  );

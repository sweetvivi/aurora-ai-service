-- Persist upstream thinking toggles so deep-think off really disables CoT at the gateway
-- (not only hide reasoning in the UI). DeepSeek / Aurora OpenAI-compatible relays honour
-- reasoning_effort=none; enable_thinking is a 百炼-specific field and is ignored there.

-- DeepSeek-family thinking models: always use reasoning_effort high/none.
UPDATE llm_models
SET capabilities_json = CASE
    WHEN capabilities_json IS NULL
      OR JSON_TYPE(capabilities_json) != 'OBJECT' THEN
        JSON_OBJECT(
            'thinkingParameter', 'reasoning_effort',
            'thinkingOnValue', 'high',
            'thinkingOffValue', 'none'
        )
    ELSE
        JSON_SET(
            capabilities_json,
            '$.thinkingParameter', 'reasoning_effort',
            '$.thinkingOnValue', 'high',
            '$.thinkingOffValue', 'none'
        )
END
WHERE model_type = 'CHAT'
  AND is_thinking = 1
  AND (
        model_key LIKE 'deepseek%'
     OR IFNULL(route, '') LIKE 'deepseek%'
  );

-- GPT / GT thinking models that still lack a parameter: seed reasoning_effort medium/none.
UPDATE llm_models
SET capabilities_json = CASE
    WHEN capabilities_json IS NULL
      OR JSON_TYPE(capabilities_json) != 'OBJECT' THEN
        JSON_OBJECT(
            'thinkingParameter', 'reasoning_effort',
            'thinkingOnValue', 'medium',
            'thinkingOffValue', 'none'
        )
    ELSE
        JSON_SET(
            capabilities_json,
            '$.thinkingParameter', 'reasoning_effort',
            '$.thinkingOnValue', 'medium',
            '$.thinkingOffValue', 'none'
        )
END
WHERE model_type = 'CHAT'
  AND is_thinking = 1
  AND (
        model_key LIKE 'gpt-%'
     OR model_key LIKE 'gt-%'
     OR IFNULL(route, '') LIKE 'gpt-%'
     OR IFNULL(route, '') LIKE 'gt-%'
  )
  AND (
        capabilities_json IS NULL
     OR JSON_EXTRACT(capabilities_json, '$.thinkingParameter') IS NULL
     OR JSON_UNQUOTE(JSON_EXTRACT(capabilities_json, '$.thinkingParameter')) = ''
  );

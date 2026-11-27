-- V12: align the agent catalog with the customAgent create/publish payload and backfill the
-- imported AI-IDE-Agent rows.
--
-- The published front-end bundle confirms these runtime types:
--   scope                        0=private, 1=public
--   classify / child_classify    the first and second values of the page's classify array
--   chat_suggest                 boolean ("nextQuestion")
--   ui_bg                        string ("background", URL or CSS color)
--   ui_type                      1=common, 2=custom
--
-- V11 introduced ui_type as a temporary VARCHAR column defaulting to CHAT. Convert it to the
-- page's numeric contract before applying the new checks. V10 was already executed, so this
-- migration also repairs its two missing import values: sn and classify.

UPDATE agents
SET ui_type = CASE
    WHEN LOWER(ui_type) IN ('1', 'common', 'chat') THEN '1'
    WHEN LOWER(ui_type) IN ('2', 'custom') THEN '2'
    ELSE '1'
END;

ALTER TABLE agents
    MODIFY COLUMN ui_type TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER agent_type,
    ADD COLUMN scope TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER ui_type,
    ADD COLUMN classify VARCHAR(128) NULL AFTER scope,
    ADD COLUMN child_classify VARCHAR(128) NULL AFTER classify,
    ADD COLUMN chat_suggest TINYINT(1) NOT NULL DEFAULT 0 AFTER child_classify,
    ADD COLUMN ui_bg VARCHAR(1024) NULL AFTER chat_suggest,
    ADD KEY idx_agents_catalog_classify
        (site_id, scope, classify, child_classify, status, sort_order),
    ADD CONSTRAINT chk_agents_ui_type CHECK (ui_type IN (1, 2)),
    ADD CONSTRAINT chk_agents_scope CHECK (scope IN (0, 1)),
    ADD CONSTRAINT chk_agents_chat_suggest CHECK (chat_suggest IN (0, 1));

-- The front matter `name` is the stable external identifier used by
-- /customDetail/index?id={sn}. V10 stored it as agent_key but did not populate sn.
UPDATE agents
SET sn = agent_key
WHERE source_path IS NOT NULL
  AND (sn IS NULL OR sn = '');

-- Imported prompts are grouped by the first path segment, e.g.
-- 编程语言专家/Python专家.md -> 编程语言专家.
UPDATE agents
SET classify = SUBSTRING_INDEX(source_path, '/', 1)
WHERE source_path IS NOT NULL
  AND (classify IS NULL OR classify = '');

-- Keep the page-level visibility discriminator consistent with the directory publication flags.
UPDATE agents
SET scope = 1
WHERE visibility = 'PUBLIC'
  AND publish_status = 'PUBLISHED';

UPDATE agents
SET published_at = COALESCE(published_at, created_at)
WHERE visibility = 'PUBLIC'
  AND publish_status = 'PUBLISHED';

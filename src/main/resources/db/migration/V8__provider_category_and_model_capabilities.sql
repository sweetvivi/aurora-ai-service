-- V8: supplier category and its own image path, plus model capability/pricing fields.

-- A supplier's category is what the console groups and filters its list by. It is **descriptive,
-- not enforced**: one gateway can legitimately serve several model types, and what actually decides
-- which endpoint may run a model is `llm_models.model_type` (checked on every call).
-- Values: CHAT / IMAGE / VIDEO / AUDIO / OTHER.
ALTER TABLE llm_providers
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'CHAT' AFTER name,
    -- Relative image path of this supplier. NULL keeps using app.llm.images-path, so existing rows
    -- behave exactly as before.
    ADD COLUMN images_path VARCHAR(255) NULL AFTER endpoint_path;

-- Whoever already owns image models is an image supplier.
UPDATE llm_providers p
SET p.category = 'IMAGE'
WHERE EXISTS (SELECT 1 FROM llm_models m WHERE m.provider_id = p.id AND m.model_type = 'IMAGE');

-- Capabilities and per-direction pricing the console needs to describe a model.
-- NULL means "not stated", which is deliberately different from false/zero: the console shows a
-- blank rather than claiming a model has no vision or costs nothing.
ALTER TABLE llm_models
    ADD COLUMN context_window INT NULL AFTER description,
    ADD COLUMN max_output_tokens INT NULL AFTER context_window,
    ADD COLUMN supports_vision TINYINT(1) NOT NULL DEFAULT 0 AFTER max_output_tokens,
    ADD COLUMN supports_tools TINYINT(1) NOT NULL DEFAULT 0 AFTER supports_vision,
    ADD COLUMN input_points_per_1k DECIMAL(20, 6) NULL AFTER supports_tools,
    ADD COLUMN output_points_per_1k DECIMAL(20, 6) NULL AFTER input_points_per_1k;

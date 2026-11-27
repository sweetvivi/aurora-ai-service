-- 记录出图用的模型，作品卡片要能显示「这张图是哪个模型产的」。
--
-- Before this column the model name only lived in the browser: `stores/imageGeneration.ts`
-- kept it in localStorage keyed by file id. Clearing the cache, switching devices or opening a
-- row older than that cache left the card with nothing to render, so it fell back to
-- 「未知模型」 — an error-looking label for perfectly good pictures.
--
-- Nullable: rows written before V91 never recorded it, and uploads / documents have no model.
ALTER TABLE uploaded_files
    ADD COLUMN model VARCHAR(64) NULL AFTER prompt;

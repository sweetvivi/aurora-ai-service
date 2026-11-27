-- Canvas materials used to always own their bytes: the image-generation page downloaded every
-- picture it had just produced and uploaded it again as a material. One picture then consumed
-- space twice (uploaded_files quota + canvas quota) and kept a second copy that deleting the work
-- in its own page could not reclaim.
--
-- A material may now point at the attachment it came from instead of duplicating it. The two kinds
-- of row are mutually exclusive: owned rows have storage_key and no source_file_id, referencing
-- rows have source_file_id and no storage_key.
ALTER TABLE ai_canvas_assets
    ADD COLUMN source_file_id BIGINT UNSIGNED NULL AFTER storage_key;

-- Referencing rows have no blob of their own, so the column has to accept NULL.
ALTER TABLE ai_canvas_assets
    MODIFY COLUMN storage_key VARCHAR(768) NULL;

-- Used to answer "does this account already link that attachment" without a full table scan.
CREATE INDEX idx_ai_canvas_assets_user_source_file
    ON ai_canvas_assets (user_id, source_file_id);

-- Classify stored assets independently from their MIME type.

ALTER TABLE uploaded_files
    ADD COLUMN asset_type VARCHAR(32) NULL AFTER extension;

-- Preserve useful semantics for rows created before this column existed. Generated and smart-edit
-- outputs use server-controlled names; legacy rows that cannot be classified remain chat attachments.
UPDATE uploaded_files
SET asset_type = CASE
    WHEN original_name LIKE 'generated-%' THEN 'generated'
    WHEN original_name LIKE 'smart-edit-%' THEN 'edit_result'
    ELSE 'chat_attachment'
END
WHERE asset_type IS NULL;

ALTER TABLE uploaded_files
    MODIFY COLUMN asset_type VARCHAR(32) NOT NULL DEFAULT 'chat_attachment';

CREATE INDEX idx_uploaded_files_user_asset_created
    ON uploaded_files (user_id, asset_type, created_at);

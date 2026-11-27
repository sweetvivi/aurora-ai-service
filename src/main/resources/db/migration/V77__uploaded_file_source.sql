-- Which feature produced an attachment.
--
-- `asset_type` says what the bytes are for (reference / generated / edit_result / chat_attachment);
-- it is storage semantics and every feature writes `generated`. It cannot answer "which page does
-- this picture belong to", so the AI-drawing gallery was listing every image of the account
-- (image generation, smart editing, chat) as if they were its own works.
--
-- `source` is the missing dimension: the owning page. NULL means "written before this column
-- existed", which no work list treats as its own.
ALTER TABLE uploaded_files
    ADD COLUMN source VARCHAR(32) NULL AFTER asset_type;

CREATE INDEX idx_uploaded_files_user_source_created
    ON uploaded_files (user_id, source, created_at);

-- Backfill from what the writers actually did before this column existed.
-- Smart-editing results are unambiguous: only that module writes `edit_result`.
UPDATE uploaded_files
SET source = 'smart_edit'
WHERE source IS NULL
  AND asset_type = 'edit_result';

-- AI drawing was the only writer that created a conversation titled `AI绘画：…` for its output.
UPDATE uploaded_files
SET source = 'painting'
WHERE source IS NULL
  AND asset_type = 'generated'
  AND conversation_id IN (SELECT id FROM conversations WHERE title LIKE 'AI绘画%');

-- Generated images without a conversation came from the image-generation page.
UPDATE uploaded_files
SET source = 'image_generation'
WHERE source IS NULL
  AND asset_type = 'generated'
  AND conversation_id IS NULL;

-- The rest are pictures produced inside a chat thread (`/images/generations` + conversationId).
UPDATE uploaded_files
SET source = 'chat'
WHERE source IS NULL
  AND asset_type = 'generated';

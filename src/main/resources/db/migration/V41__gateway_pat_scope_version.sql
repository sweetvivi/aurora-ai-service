-- Tracks which New API PAT grant revision has been applied. Existing accounts start at zero so
-- the first detailed-usage request can widen the current PAT in place without rotating secrets.

ALTER TABLE user_gateway_tokens
    ADD COLUMN pat_scope_version INT NOT NULL DEFAULT 0 AFTER pat_tag;

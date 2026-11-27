-- Message versions for edit / regenerate (ChatGPT-style 1/N), without deleting history.

ALTER TABLE chat_messages
    ADD COLUMN turn_group_id BIGINT UNSIGNED NULL AFTER sequence_no,
    ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER turn_group_id,
    ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1 AFTER version;

-- Each existing row is its own turn group (id == turn_group_id).
UPDATE chat_messages SET turn_group_id = id WHERE turn_group_id IS NULL;

ALTER TABLE chat_messages
    MODIFY COLUMN turn_group_id BIGINT UNSIGNED NOT NULL;

CREATE INDEX idx_chat_messages_turn_group
    ON chat_messages (conversation_id, turn_group_id, version);

CREATE INDEX idx_chat_messages_active_sequence
    ON chat_messages (conversation_id, active, sequence_no);

ALTER TABLE chat_messages
    ADD COLUMN artifact_context_json LONGTEXT NULL AFTER attachments_json;

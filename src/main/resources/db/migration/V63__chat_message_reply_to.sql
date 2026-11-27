-- Link each assistant turn to the user message it answers (edit version pair switch).

ALTER TABLE chat_messages
    ADD COLUMN reply_to_message_id BIGINT UNSIGNED NULL AFTER active;

-- Backfill via a temp table: MySQL rejects UPDATE ... FROM the same table (error 1093),
-- even when wrapped in a derived table on some 8.0.x builds.
CREATE TEMPORARY TABLE tmp_chat_reply_to AS
SELECT a.id AS assistant_id, u.id AS user_message_id
FROM chat_messages a
    INNER JOIN chat_messages u
        ON u.conversation_id = a.conversation_id
       AND u.role = 'user'
       AND u.sequence_no = (
            SELECT MAX(u2.sequence_no)
            FROM chat_messages u2
            WHERE u2.conversation_id = a.conversation_id
              AND u2.role = 'user'
              AND u2.sequence_no < a.sequence_no
        )
WHERE a.role = 'assistant';

UPDATE chat_messages a
    INNER JOIN tmp_chat_reply_to t ON t.assistant_id = a.id
SET a.reply_to_message_id = t.user_message_id
WHERE a.reply_to_message_id IS NULL;

DROP TEMPORARY TABLE tmp_chat_reply_to;

CREATE INDEX idx_chat_messages_reply_to
    ON chat_messages (conversation_id, reply_to_message_id);

-- Message versions for agent edit / regenerate, mirroring chat_messages (V62).
--
-- Agent chat needed the same 1/N semantics the plain chat got in V62/V63: editing an earlier
-- question or asking for another answer must keep the old one instead of overwriting it. The
-- structure is identical because a turn is the same shape here — one user row and one assistant
-- row (tool calls ride along inside the assistant row's tool_calls_json).

ALTER TABLE agent_chat_messages
    ADD COLUMN turn_group_id BIGINT UNSIGNED NULL AFTER sequence_no,
    ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER turn_group_id,
    ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1 AFTER version,
    ADD COLUMN reply_to_message_id BIGINT UNSIGNED NULL AFTER active;

-- Rows written before this migration each become their own group. They have no siblings, so the
-- 1/N switcher stays hidden for them and the list looks exactly as it did before.
UPDATE agent_chat_messages SET turn_group_id = id WHERE turn_group_id IS NULL;

ALTER TABLE agent_chat_messages
    MODIFY COLUMN turn_group_id BIGINT UNSIGNED NOT NULL;

-- The switcher reads one group's versions in order.
CREATE INDEX idx_agent_chat_messages_turn_group
    ON agent_chat_messages (agent_conversation_id, turn_group_id, version);

-- Context rebuild takes only the active tail; this index keeps that query off a full scan.
CREATE INDEX idx_agent_chat_messages_active_sequence
    ON agent_chat_messages (agent_conversation_id, active, sequence_no);

-- Activating a user version also has to find the assistant that answers it.
CREATE INDEX idx_agent_chat_messages_reply_to
    ON agent_chat_messages (agent_conversation_id, reply_to_message_id);

-- Backfill reply_to for existing turns: each assistant row answers the nearest earlier user row in
-- the same conversation.
--
-- Via a temp table, not a correlated subquery: MySQL refuses "UPDATE t ... WHERE x IN (SELECT ...
-- FROM t)" with error 1093 ("You can't specify target table for update in FROM clause"). V63 hit
-- exactly this on chat_messages and solved it the same way.
CREATE TEMPORARY TABLE tmp_agent_reply_to AS
SELECT a.id AS assistant_id, u.id AS user_message_id
FROM agent_chat_messages a
    INNER JOIN agent_chat_messages u
        ON u.agent_conversation_id = a.agent_conversation_id
       AND u.role = 'user'
       AND u.sequence_no = (
            SELECT MAX(u2.sequence_no)
            FROM agent_chat_messages u2
            WHERE u2.agent_conversation_id = a.agent_conversation_id
              AND u2.role = 'user'
              AND u2.sequence_no < a.sequence_no
        )
WHERE a.role = 'assistant';

UPDATE agent_chat_messages a
    INNER JOIN tmp_agent_reply_to t ON t.assistant_id = a.id
SET a.reply_to_message_id = t.user_message_id
WHERE a.reply_to_message_id IS NULL;

DROP TEMPORARY TABLE tmp_agent_reply_to;

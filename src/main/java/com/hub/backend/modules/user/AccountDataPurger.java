package com.hub.backend.modules.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Removes everything that belongs to an account, so the account row itself can be deleted.
 *
 * <p>Almost every user-scoped table points at {@code user_accounts} with the default
 * {@code RESTRICT} rule, so the account row cannot go first: the children have to be cleared
 * before it, inside the same transaction.</p>
 */
@Service
public class AccountDataPurger {

    /**
     * User-owned tables in deletion order: every child comes before the parent it references.
     *
     * <p>The order is not alphabetical on purpose. {@code conversations} points at
     * {@code conversation_groups} and {@code points_ledger} at {@code points_accounts}, so
     * deleting a parent first would trip the foreign key. The list is maintained by hand because
     * it has to run on both MySQL and H2, and the two disagree on how {@code information_schema}
     * exposes foreign-key targets. A migration that adds another user-scoped table must add it
     * here; leaving it out fails loudly at deletion time instead of quietly orphaning rows.</p>
     */
    private static final List<String> USER_SCOPED_TABLES = List.of(
            "agent_favorites",
            "agent_message_feedback",
            "agent_runs",
            "ai_canvas_assets",
            "ai_canvas_tasks",
            "ai_canvas_chat_sessions",
            "ai_canvas_workflows",
            "async_tasks",
            "auth_sessions",
            "chat_usage",
            "commerce_generation_history",
            "conversation_shares",
            "message_feedback",
            "newapi_accounts",
            "points_ledger",
            "ppt_jobs",
            "smart_edit_records",
            "thesis_documents",
            "uploaded_files",
            "user_gateway_tokens",
            "user_identities",
            "user_notification_reads",
            "agent_conversations",
            "conversations",
            "points_accounts",
            "conversation_groups");

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Deletes the account's own rows and releases the agents it published.
     *
     * <p>Agents are detached rather than deleted: another account's conversation may still point at
     * one, and removing it would take that conversation with it. A NULL owner already means
     * "published by the platform" in this schema, so the agent survives as a platform agent
     * instead of becoming an orphan that references a row no longer there.</p>
     */
    @Transactional
    public void purge(Long userId) {
        Set<String> present = existingTables();
        for (String table : USER_SCOPED_TABLES) {
            // The test profile builds the schema from the entities, so a table that has no entity
            // is genuinely absent there while existing in production. Skipping is correct: there
            // is nothing to delete, and the production run still clears everything.
            if (!present.contains(table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            entityManager.createNativeQuery(
                            "delete from `" + table + "` where `user_id` = :userId")
                    .setParameter("userId", userId)
                    .executeUpdate();
        }
        if (present.contains("agents")) {
            entityManager.createNativeQuery(
                            "update `agents` set `owner_user_id` = null where `owner_user_id` = :userId")
                    .setParameter("userId", userId)
                    .executeUpdate();
        }
    }

    /** Lower-cased names of the tables that actually exist in the current schema. */
    private Set<String> existingTables() {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            Set<String> names = new HashSet<>();
            try (ResultSet tables = connection.getMetaData()
                    .getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (name != null) {
                        names.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return names;
        });
    }
}

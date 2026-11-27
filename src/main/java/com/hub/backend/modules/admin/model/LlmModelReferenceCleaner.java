package com.hub.backend.modules.admin.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Clears everything that points at a model row which is about to be deleted.
 *
 * <p>{@code llm_models} is referenced by ten columns across nine tables, and only one of them
 * cascades. Deleting a model directly therefore fails on InnoDB the moment any conversation,
 * message or usage line ever picked it — this is exactly what happened to migration V80, where a
 * missed foreign key blocked startup entirely (fixed later in V85).</p>
 *
 * <p><b>Why JDBC and not the JPA repositories.</b> Three of these table/column pairs have no entity
 * mapped ({@code conversation_group_models}, {@code chat_usage} and the {@code parent_model_id}
 * self reference are written by services through other paths), and the remaining ones would each
 * need a hand-written {@code @Modifying} bulk delete. One place with the full list is easier to
 * audit against the schema than seven scattered repository methods — and V85 is the reference for
 * which columns are nullable and which rows must go.</p>
 *
 * <p>Two rules, taken from V85:</p>
 * <ul>
 *   <li><b>Not-null join columns are deleted.</b> The row carries nothing but the pairing, so
 *       losing it costs no history.</li>
 *   <li><b>Nullable reference columns are nulled.</b> A conversation, a message or a usage line is
 *       still meaningful history; it simply stops pointing at a model that no longer exists.</li>
 * </ul>
 */
@Repository
public class LlmModelReferenceCleaner {

    /** Join tables whose {@code model_id} is NOT NULL: the row exists only for the pairing. */
    private static final List<String> JOIN_TABLES = List.of(
            "llm_model_tags",
            "agent_models",
            "conversation_group_models",
            "llm_model_fallback_members");

    /** Tables whose {@code model_id} is nullable: keep the history, drop the reference. */
    private static final List<String> NULLABLE_REFERENCES = List.of(
            "conversations",
            "chat_messages",
            "chat_usage",
            "agent_conversations",
            "agent_chat_messages");

    private final JdbcTemplate jdbc;

    public LlmModelReferenceCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Removes every reference to the given models.
     *
     * <p>Must run before the rows leave {@code llm_models}, inside the same transaction. The ids are
     * expanded into one {@code IN (...)} per table so a 100-row purge issues a dozen statements
     * instead of a thousand.</p>
     */
    public void detach(Collection<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(modelIds);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.toArray();

        // A variant loses its parent but stays in the catalogue.
        update("llm_models", "update llm_models set parent_model_id = null "
                + "where parent_model_id in (" + placeholders + ")", args);

        for (String table : JOIN_TABLES) {
            update(table, "delete from " + table + " where model_id in (" + placeholders + ")", args);
        }
        for (String table : NULLABLE_REFERENCES) {
            update(table, "update " + table + " set model_id = null where model_id in ("
                    + placeholders + ")", args);
        }
    }

    /**
     * Runs one statement, skipping tables the current schema does not have.
     *
     * <p>Production always has all of them (Flyway owns the schema), but the H2 test profile builds
     * its schema from the JPA entities with {@code ddl-auto=create-drop} and Flyway disabled, so a
     * table that no entity maps — {@code chat_usage}, {@code conversation_group_models} — simply is
     * not there. Guarding keeps the same cleaner usable in both.</p>
     */
    private void update(String table, String sql, Object[] args) {
        if (!tableExists(table)) {
            return;
        }
        jdbc.update(sql, args);
    }

    private boolean tableExists(String table) {
        try {
            Boolean exists = jdbc.execute((java.sql.Connection connection) -> {
                try (var tables = connection.getMetaData()
                        .getTables(null, null, "%", new String[]{"TABLE"})) {
                    while (tables.next()) {
                        String name = tables.getString("TABLE_NAME");
                        if (name != null && name.equalsIgnoreCase(table)) {
                            return Boolean.TRUE;
                        }
                    }
                }
                return Boolean.FALSE;
            });
            return Boolean.TRUE.equals(exists);
        } catch (Exception exception) {
            // Metadata unavailable: assume the table is there and let the statement speak for itself.
            return true;
        }
    }
}

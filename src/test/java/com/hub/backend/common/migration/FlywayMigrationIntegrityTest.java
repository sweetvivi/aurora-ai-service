package com.hub.backend.common.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Map.entry;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayMigrationIntegrityTest {

    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V(\\d+)__[^/]+\\.sql$");

    @Test
    void migrationVersionsAreUniqueAndSequential() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        assertTrue(resources.length > 0, "No Flyway migrations were found on the classpath");

        List<Integer> versions = Arrays.stream(resources)
                .map(Resource::getFilename)
                .map(this::parseVersion)
                .sorted()
                .toList();

        List<Integer> expected = IntStream.rangeClosed(1, versions.size()).boxed().toList();
        assertEquals(expected, versions, "Flyway versions must be unique and start at V1 without gaps");
    }

    @Test
    void chatAttachmentMigrationAddsNullableAttachmentReferences() throws IOException {
        Resource migration = new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V57__chat_message_attachments.sql");
        String sql;
        try (var input = migration.getInputStream()) {
            sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("ALTER TABLE chat_messages ADD COLUMN attachments_json LONGTEXT NULL"));
    }

    @Test
    void publishedMigrationVersionsKeepTheirNames() throws IOException {
        Map<Integer, String> published = Map.ofEntries(
                entry(23, "V23__newapi_catalog_sync.sql"),
                entry(24, "V24__admin_user_remark.sql"),
                entry(25, "V25__video_generation.sql"),
                entry(26, "V26__video_model_capabilities.sql"),
                entry(27, "V27__commerce_tools.sql"),
                entry(28, "V28__commerce_tool_form_schema.sql"),
                entry(29, "V29__commerce_tool_icon_url.sql"),
                entry(30, "V30__commerce_schema_replace_and_background.sql"),
                entry(31, "V31__commerce_schema_remaining.sql"),
                entry(32, "V32__agent_message_tool_calls.sql"),
                entry(33, "V33__agent_message_attachments.sql"),
                entry(34, "V34__super_agent_seed.sql"),
                entry(35, "V35__agent_linked_tool.sql"));

        Map<Integer, String> actual = Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/V*__*.sql"))
                .map(Resource::getFilename)
                .filter(filename -> published.containsKey(parseVersion(filename)))
                .collect(Collectors.toMap(this::parseVersion, filename -> filename));

        assertEquals(
                published,
                actual,
                "Published Flyway versions cannot be renumbered without rewriting existing databases");
    }

    private int parseVersion(String filename) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unexpected Flyway migration filename: " + filename);
        }
        return Integer.parseInt(matcher.group(1));
    }
}

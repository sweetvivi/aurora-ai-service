package com.hub.backend.modules.admin.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Repository
public class AdminDashboardRepository {

    private static final String TOTAL_TOKENS_SQL = """
            select
                coalesce((
                    select sum(m.total_tokens)
                      from chat_messages m
                      join conversations c on c.id = m.conversation_id
                      join user_accounts u on u.id = c.user_id
                     where u.site_id = ?
                       and m.total_tokens is not null
                ), 0)
                +
                coalesce((
                    select sum(m.total_tokens)
                      from agent_chat_messages m
                      join agent_conversations c on c.id = m.agent_conversation_id
                      join user_accounts u on u.id = c.user_id
                     where u.site_id = ?
                       and m.total_tokens is not null
                ), 0)
            """;

    private static final String NEW_USERS_SQL = """
            select cast(created_at as date) as user_date,
                   count(*) as user_count
              from user_accounts
             where site_id = ?
               and created_at >= ?
               and created_at < ?
             group by cast(created_at as date)
            """;

    private final JdbcTemplate jdbcTemplate;

    public AdminDashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long totalVisits(Long siteId) {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from site_visits where site_id = ?",
                Long.class,
                siteId);
        return value == null ? 0 : value;
    }

    public long totalTokens(Long siteId) {
        Long value = jdbcTemplate.queryForObject(
                TOTAL_TOKENS_SQL,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                siteId,
                siteId);
        return value == null ? 0 : value;
    }

    public Map<LocalDate, Long> newUsersByDay(Long siteId,
                                              LocalDateTime from,
                                              LocalDateTime to) {
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbcTemplate.query(NEW_USERS_SQL, resultSet -> {
            counts.put(
                    resultSet.getDate("user_date").toLocalDate(),
                    resultSet.getLong("user_count"));
        }, siteId, from, to);
        return counts;
    }
}

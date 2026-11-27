package com.hub.backend.modules.gateway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Repository
public class TokenUsageHistoryRepository {

    private static final String CHAT_USAGE_SQL = """
            select cast(m.created_at as date) as usage_date,
                   coalesce(sum(m.prompt_tokens), 0) as prompt_tokens,
                   coalesce(sum(m.completion_tokens), 0) as completion_tokens,
                   coalesce(sum(m.total_tokens), 0) as total_tokens
              from chat_messages m
              join conversations c on c.id = m.conversation_id
             where c.user_id = ?
               and m.created_at >= ?
               and m.created_at < ?
               and m.total_tokens is not null
             group by cast(m.created_at as date)
            """;

    private static final String AGENT_USAGE_SQL = """
            select cast(m.created_at as date) as usage_date,
                   coalesce(sum(m.prompt_tokens), 0) as prompt_tokens,
                   coalesce(sum(m.completion_tokens), 0) as completion_tokens,
                   coalesce(sum(m.total_tokens), 0) as total_tokens
              from agent_chat_messages m
              join agent_conversations c on c.id = m.agent_conversation_id
             where c.user_id = ?
               and m.created_at >= ?
               and m.created_at < ?
               and m.total_tokens is not null
             group by cast(m.created_at as date)
            """;

    private final JdbcTemplate jdbcTemplate;

    public TokenUsageHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<LocalDate, TokenTotals> usageByDay(Long userId,
                                                  LocalDateTime from,
                                                  LocalDateTime to) {
        Map<LocalDate, TokenTotals> totals = new HashMap<>();
        collect(CHAT_USAGE_SQL, userId, from, to, totals);
        collect(AGENT_USAGE_SQL, userId, from, to, totals);
        return totals;
    }

    private void collect(String sql,
                         Long userId,
                         LocalDateTime from,
                         LocalDateTime to,
                         Map<LocalDate, TokenTotals> totals) {
        jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            TokenTotals row = readTotals(resultSet);
            totals.merge(row.date(), row, TokenTotals::plus);
            return row;
        }, userId, from, to);
    }

    private static TokenTotals readTotals(ResultSet resultSet) throws SQLException {
        return new TokenTotals(
                resultSet.getDate("usage_date").toLocalDate(),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getLong("total_tokens"));
    }

    public record TokenTotals(
            LocalDate date,
            long promptTokens,
            long completionTokens,
            long totalTokens
    ) {
        TokenTotals plus(TokenTotals other) {
            return new TokenTotals(
                    date,
                    promptTokens + other.promptTokens,
                    completionTokens + other.completionTokens,
                    totalTokens + other.totalTokens);
        }
    }
}

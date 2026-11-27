package com.hub.backend.modules.gateway;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.dto.TokenUsageHistoryResponse;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TokenUsageHistoryService {

    private static final int MAX_DAYS = 90;
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Shanghai");

    private final TokenUsageHistoryRepository repository;
    private final Clock clock;

    public TokenUsageHistoryService(TokenUsageHistoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public TokenUsageHistoryResponse history(CurrentUser user, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidRequestException("days must be between 1 and " + MAX_DAYS);
        }

        LocalDate today = LocalDate.now(clock.withZone(USAGE_ZONE));
        LocalDate from = today.minusDays(days - 1L);
        Map<LocalDate, TokenUsageHistoryRepository.TokenTotals> totals =
                repository.usageByDay(
                        user.userId(),
                        from.atStartOfDay(),
                        today.plusDays(1).atStartOfDay());

        List<TokenUsageHistoryResponse.DailyUsage> daily = new ArrayList<>(days);
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            TokenUsageHistoryRepository.TokenTotals row = totals.get(date);
            long rowPrompt = row == null ? 0 : row.promptTokens();
            long rowCompletion = row == null ? 0 : row.completionTokens();
            long rowTotal = row == null ? 0 : row.totalTokens();
            daily.add(new TokenUsageHistoryResponse.DailyUsage(
                    date, rowPrompt, rowCompletion, rowTotal));
            promptTokens += rowPrompt;
            completionTokens += rowCompletion;
            totalTokens += rowTotal;
        }

        return new TokenUsageHistoryResponse(
                days,
                from,
                today,
                promptTokens,
                completionTokens,
                totalTokens,
                daily);
    }
}

package com.hub.backend.modules.gateway;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.dto.TokenUsageHistoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenUsageHistoryServiceTest {

    @Mock
    private TokenUsageHistoryRepository repository;

    @Test
    void fillsDatesWithoutUsageAndCombinesTotals() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-22T08:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        TokenUsageHistoryService service = new TokenUsageHistoryService(repository, clock);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.systemDefault()));
        LocalDate usedDate = today.minusDays(1);
        when(repository.usageByDay(eq(7L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Map.of(
                        usedDate,
                        new TokenUsageHistoryRepository.TokenTotals(usedDate, 120, 80, 200)));

        TokenUsageHistoryResponse response =
                service.history(new CurrentUser(7L, 1L, "demo"), 7);

        assertThat(response.daily()).hasSize(7);
        assertThat(response.totalPromptTokens()).isEqualTo(120);
        assertThat(response.totalCompletionTokens()).isEqualTo(80);
        assertThat(response.totalTokens()).isEqualTo(200);
        assertThat(response.daily())
                .filteredOn(day -> day.date().equals(usedDate))
                .singleElement()
                .extracting(TokenUsageHistoryResponse.DailyUsage::totalTokens)
                .isEqualTo(200L);
        assertThat(response.daily().get(response.daily().size() - 1).date()).isEqualTo(today);
        verify(repository).usageByDay(
                eq(7L),
                eq(today.minusDays(6).atStartOfDay()),
                eq(today.plusDays(1).atStartOfDay()));
    }

    @Test
    void rejectsPeriodsLongerThanNinetyDays() {
        TokenUsageHistoryService service = new TokenUsageHistoryService(
                repository,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.history(new CurrentUser(7L, 1L, "demo"), 91))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("between 1 and 90");
    }
}

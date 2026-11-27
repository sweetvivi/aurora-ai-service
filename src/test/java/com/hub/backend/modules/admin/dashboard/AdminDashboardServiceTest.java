package com.hub.backend.modules.admin.dashboard;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock
    private AppSiteRepository siteRepository;

    @Mock
    private AdminDashboardRepository repository;

    @Test
    void combinesSiteTotalsAndFillsMissingUserDays() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-24T04:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        AppSiteEntity site = new AppSiteEntity("default", "Default Site");
        ReflectionTestUtils.setField(site, "id", 7L);
        LocalDate today = LocalDate.of(2026, 10, 24);
        LocalDate yesterday = today.minusDays(1);
        AppProperties appProperties = new AppProperties();

        when(siteRepository.findBySiteKey("default")).thenReturn(Optional.of(site));
        when(repository.totalVisits(7L)).thenReturn(128L);
        when(repository.totalTokens(7L)).thenReturn(9_876_543L);
        when(repository.newUsersByDay(
                eq(7L),
                any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(Map.of(yesterday, 3L));

        AdminDashboardService service = new AdminDashboardService(
                siteRepository,
                appProperties,
                repository,
                clock);

        AdminDashboardStatsResponse response = service.stats(7);

        assertThat(response.totalVisits()).isEqualTo(128L);
        assertThat(response.totalTokens()).isEqualTo(9_876_543L);
        assertThat(response.newUsers().days()).isEqualTo(7);
        assertThat(response.newUsers().daily()).hasSize(7);
        assertThat(response.newUsers().daily().get(5).date()).isEqualTo(yesterday);
        assertThat(response.newUsers().daily().get(5).count()).isEqualTo(3L);
        assertThat(response.newUsers().daily().get(6).date()).isEqualTo(today);
        assertThat(response.newUsers().daily().get(6).count()).isZero();
        assertThat(response.generatedAt()).isEqualTo(clock.instant());
        verify(repository).newUsersByDay(
                7L,
                today.minusDays(6).atStartOfDay(),
                today.plusDays(1).atStartOfDay());
    }

    @Test
    void rejectsPeriodsLongerThanNinetyDays() {
        AdminDashboardService service = new AdminDashboardService(
                siteRepository,
                new AppProperties(),
                repository,
                Clock.systemUTC());

        assertThatThrownBy(() -> service.stats(91))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("between 1 and 90");
    }
}

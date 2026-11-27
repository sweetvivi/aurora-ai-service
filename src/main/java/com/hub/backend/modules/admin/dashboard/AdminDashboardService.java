package com.hub.backend.modules.admin.dashboard;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    private static final int MAX_DAYS = 90;
    private static final ZoneId DASHBOARD_ZONE = ZoneId.of("Asia/Shanghai");

    private final AppSiteRepository siteRepository;
    private final AppProperties appProperties;
    private final AdminDashboardRepository repository;
    private final Clock clock;

    public AdminDashboardService(AppSiteRepository siteRepository,
                                 AppProperties appProperties,
                                 AdminDashboardRepository repository,
                                 Clock clock) {
        this.siteRepository = siteRepository;
        this.appProperties = appProperties;
        this.repository = repository;
        this.clock = clock;
    }

    public AdminDashboardStatsResponse stats(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidRequestException("days must be between 1 and " + MAX_DAYS);
        }

        Long siteId = defaultSite().getId();
        LocalDate today = LocalDate.now(clock.withZone(DASHBOARD_ZONE));
        LocalDate from = today.minusDays(days - 1L);
        Map<LocalDate, Long> newUsers = repository.newUsersByDay(
                siteId,
                from.atStartOfDay(),
                today.plusDays(1).atStartOfDay());

        List<AdminDashboardStatsResponse.DailyNewUser> daily = new ArrayList<>(days);
        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            daily.add(new AdminDashboardStatsResponse.DailyNewUser(
                    date,
                    newUsers.getOrDefault(date, 0L)));
        }

        return new AdminDashboardStatsResponse(
                repository.totalVisits(siteId),
                repository.totalTokens(siteId),
                new AdminDashboardStatsResponse.NewUserTrend(days, daily),
                Instant.now(clock));
    }

    private AppSiteEntity defaultSite() {
        String siteKey = appProperties.getDefaultKey();
        return siteRepository.findBySiteKey(siteKey)
                .orElseThrow(() -> new NotFoundException(
                        "Default site '%s' was not found".formatted(siteKey)));
    }
}

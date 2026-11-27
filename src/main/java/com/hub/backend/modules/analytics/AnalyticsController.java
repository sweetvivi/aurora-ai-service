package com.hub.backend.modules.analytics;

import com.hub.backend.modules.analytics.dto.SiteVisitRequest;
import com.hub.backend.modules.analytics.dto.SiteVisitResponse;
import com.hub.backend.modules.appconfig.ChannelResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "公开 · 访问统计", description = "前台页面访问上报")
public class AnalyticsController {

    private final ChannelResolver channelResolver;
    private final SiteVisitService siteVisitService;

    public AnalyticsController(ChannelResolver channelResolver,
                               SiteVisitService siteVisitService) {
        this.channelResolver = channelResolver;
        this.siteVisitService = siteVisitService;
    }

    @Operation(summary = "记录一次页面访问")
    @PostMapping("/visits")
    public SiteVisitResponse recordVisit(@Valid @RequestBody SiteVisitRequest request,
                                         HttpServletRequest httpRequest) {
        Long siteId = channelResolver.resolve(httpRequest).getId();
        return siteVisitService.record(siteId, request.path());
    }
}

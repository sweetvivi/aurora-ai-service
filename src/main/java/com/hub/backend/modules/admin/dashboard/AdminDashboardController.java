package com.hub.backend.modules.admin.dashboard;

import com.hub.backend.modules.admin.provider.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/dashboard")
@Tag(name = "管理 · 数据概览", description = "站点访问、Token 消耗与新增用户趋势")
public class AdminDashboardController {

    private final AdminDashboardService service;
    private final AdminGuard adminGuard;

    public AdminDashboardController(AdminDashboardService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "数据概览统计")
    @GetMapping("/stats")
    public AdminDashboardStatsResponse stats(
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.stats(days);
    }
}

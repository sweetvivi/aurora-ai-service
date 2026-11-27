package com.hub.backend.modules.admin.logging;

import com.hub.backend.modules.admin.operations.AdminListResponse;
import com.hub.backend.modules.admin.provider.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「运行日志」查询接口。
 *
 * <p>两组数据：{@code request_logs}（一次 HTTP 请求一行，回答「谁在什么时候调了什么、
 * 结果如何」）与 {@code audit_logs}（一次写操作一行，回答「谁把哪个对象从什么改成了什么」）。
 * 两者用 {@code trace_id} 串起来：从审计记录点进去，能拿到那次请求的完整访问日志。</p>
 *
 * <p>全部是 GET，管理密钥走 {@link AdminGuard}，与其余管理接口一致。这类接口不需要审计——
 * 审计的对象是「改变了什么」，查日志本身不改变任何状态。</p>
 */
@RestController
@RequestMapping("/api/admin/v1/logs")
@Tag(name = "管理 · 运行日志", description = "访问日志、审计日志与统计概览")
public class AdminLogController {

    private final AdminLogService logService;
    private final AdminGuard adminGuard;

    public AdminLogController(AdminLogService logService, AdminGuard adminGuard) {
        this.logService = logService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "访问日志列表")
    @GetMapping("/requests")
    public AdminListResponse<AdminRequestLogResponse> requests(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return logService.requests(new AdminLogQuery(
                keyword, level, outcome, method, status, userId, traceId, from, to, page, pageSize));
    }

    @Operation(summary = "访问日志详情（含完整请求体与错误明细）")
    @GetMapping("/requests/{id}")
    public AdminRequestLogResponse requestDetail(@PathVariable long id,
                                                 HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return logService.requestDetail(id);
    }

    @Operation(summary = "审计日志列表")
    @GetMapping("/audits")
    public AdminListResponse<AdminAuditLogResponse> audits(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return logService.audits(new AdminAuditLogQuery(
                keyword, action, target, outcome, from, to, page, pageSize));
    }

    @Operation(summary = "审计日志详情（含变更前后快照）")
    @GetMapping("/audits/{id}")
    public AdminAuditLogResponse auditDetail(@PathVariable long id,
                                             HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return logService.auditDetail(id);
    }

    /**
     * @param hours 未给 from/to 时使用的窗口（小时）；给了 from/to 就忽略它。
     */
    @Operation(summary = "运行日志概览统计")
    @GetMapping("/overview")
    public AdminLogOverviewResponse overview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer hours,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return logService.overview(from, to, hours);
    }
}

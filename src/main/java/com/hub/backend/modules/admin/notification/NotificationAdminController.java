package com.hub.backend.modules.admin.notification;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.notification.NotificationService;
import com.hub.backend.modules.notification.dto.AdminNotificationRequest;
import com.hub.backend.modules.notification.dto.AdminNotificationResponse;
import com.hub.backend.modules.notification.dto.NotificationStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "NOTIFICATION")
@RestController
@RequestMapping("/api/admin/v1/notifications")
@Tag(name = "管理 · 站内通知", description = "发布、编辑、撤回和删除站内通知")
public class NotificationAdminController {

    private final NotificationService notificationService;
    private final AdminGuard adminGuard;

    public NotificationAdminController(NotificationService notificationService,
                                       AdminGuard adminGuard) {
        this.notificationService = notificationService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "通知列表")
    @GetMapping
    public List<AdminNotificationResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        adminGuard.require(request);
        return notificationService.adminList(status, siteId, keyword);
    }

    @Operation(summary = "创建通知")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "已创建"),
            @ApiResponse(responseCode = "400", description = "字段校验失败或站点不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<AdminNotificationResponse> create(
            @Valid @RequestBody AdminNotificationRequest body,
            HttpServletRequest request) {
        adminGuard.require(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(notificationService.create(body));
    }

    @Operation(summary = "编辑通知")
    @PutMapping("/{id}")
    public AdminNotificationResponse update(@PathVariable Long id,
                                            @Valid @RequestBody AdminNotificationRequest body,
                                            HttpServletRequest request) {
        adminGuard.require(request);
        return notificationService.update(id, body);
    }

    @Operation(summary = "发布或撤回通知")
    @PutMapping("/{id}/status")
    public AdminNotificationResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody NotificationStatusRequest body,
            HttpServletRequest request) {
        adminGuard.require(request);
        return notificationService.updateStatus(id, body);
    }

    @Operation(summary = "删除通知")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        adminGuard.require(request);
        notificationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

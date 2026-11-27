package com.hub.backend.modules.admin.operations;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1")
@Tag(name = "管理 · 用户与会话", description = "用户、会话和图片任务管理接口")
public class AdminOperationsController {

    private final AdminOperationsService operationsService;
    private final AdminGuard adminGuard;

    public AdminOperationsController(AdminOperationsService operationsService,
                                     AdminGuard adminGuard) {
        this.operationsService = operationsService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "用户列表")
    @GetMapping("/users")
    public AdminListResponse<AdminUserResponse> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.users(keyword, status, page, pageSize);
    }

    @Operation(summary = "新增用户")
    // 建号：before 为空（那时还没有这个用户），after 是创建出来的完整行。
    @AuditLog(action = "CREATE_USER", target = "USER_ACCOUNT")
    @PostMapping("/users")
    public AdminUserResponse createUser(@Valid @RequestBody AdminCreateUserRequest request,
                                        HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.createUser(request);
    }

    @Operation(summary = "启用或停用用户")
    // 停用/启用是权限变更，属于最容易被追问「谁干的」的一类操作。
    @AuditLog(action = "UPDATE_USER_STATUS", target = "USER_ACCOUNT", id = "#id")
    @PatchMapping("/users/{id}/status")
    public AdminOperationResponse updateUserStatus(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserStatusRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.updateUserStatus(id, request);
    }

    @Operation(summary = "删除用户")
    @AuditLog(action = "DELETE_USER", target = "USER_ACCOUNT", id = "#id")
    @DeleteMapping("/users/{id}")
    public AdminOperationResponse deleteUser(@PathVariable Long id,
                                             HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.deleteUser(id);
    }

    @Operation(summary = "会话列表")
    @GetMapping("/conversations")
    public AdminListResponse<AdminConversationResponse> conversations(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.conversations(keyword, page, pageSize);
    }

    @Operation(summary = "删除会话")
    @AuditLog(action = "DELETE_CONVERSATION", target = "CONVERSATION", id = "#id")
    @DeleteMapping("/conversations/{id}")
    public AdminOperationResponse deleteConversation(@PathVariable Long id,
                                                     HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.deleteConversation(id);
    }

    @Operation(summary = "图片任务列表")
    @GetMapping("/tasks")
    public AdminListResponse<AdminTaskResponse> tasks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return operationsService.tasks(keyword, status, page, pageSize);
    }
}

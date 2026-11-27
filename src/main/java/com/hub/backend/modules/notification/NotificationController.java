package com.hub.backend.modules.notification;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.notification.dto.ReadAllResponse;
import com.hub.backend.modules.notification.dto.UnreadCountResponse;
import com.hub.backend.modules.notification.dto.UserNotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "站内通知", description = "当前用户的站内消息与已读状态")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUserResolver currentUserResolver;

    public NotificationController(NotificationService notificationService,
                                  CurrentUserResolver currentUserResolver) {
        this.notificationService = notificationService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "通知列表")
    @GetMapping
    public List<UserNotificationResponse> list(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        CurrentUser user = currentUserResolver.requireAuthenticated(request);
        return notificationService.userList(user, limit);
    }

    @Operation(summary = "未读通知数")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(HttpServletRequest request) {
        return notificationService.unreadCount(
                currentUserResolver.requireAuthenticated(request));
    }

    @Operation(summary = "标记单条通知已读")
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, HttpServletRequest request) {
        notificationService.markRead(
                currentUserResolver.requireAuthenticated(request), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public ReadAllResponse markAllRead(HttpServletRequest request) {
        return notificationService.markAllRead(
                currentUserResolver.requireAuthenticated(request));
    }
}

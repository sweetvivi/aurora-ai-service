package com.hub.backend.modules.notification;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.notification.dto.AdminNotificationRequest;
import com.hub.backend.modules.notification.dto.AdminNotificationResponse;
import com.hub.backend.modules.notification.dto.NotificationStatusRequest;
import com.hub.backend.modules.notification.dto.ReadAllResponse;
import com.hub.backend.modules.notification.dto.UnreadCountResponse;
import com.hub.backend.modules.notification.dto.UserNotificationResponse;
import com.hub.backend.modules.notification.entity.SiteNotificationEntity;
import com.hub.backend.modules.notification.entity.UserNotificationReadEntity;
import com.hub.backend.modules.notification.repository.SiteNotificationRepository;
import com.hub.backend.modules.notification.repository.UserNotificationReadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class NotificationService {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_CONTENT_LENGTH = 10000;
    private static final int MAX_LIST_LIMIT = 100;

    private final SiteNotificationRepository notifications;
    private final UserNotificationReadRepository reads;
    private final AppSiteRepository sites;

    public NotificationService(SiteNotificationRepository notifications,
                               UserNotificationReadRepository reads,
                               AppSiteRepository sites) {
        this.notifications = notifications;
        this.reads = reads;
        this.sites = sites;
    }

    public List<AdminNotificationResponse> adminList(String status, Long siteId, String keyword) {
        String statusFilter = optionalStatusFilter(status);
        if (siteId != null && siteId < 0) {
            throw new InvalidRequestException("siteId must not be negative");
        }
        String keywordFilter = keyword == null ? null : keyword.strip().toLowerCase(Locale.ROOT);

        return notifications.findAllByOrderByCreatedAtDescIdDesc().stream()
                .filter(item -> statusFilter == null || statusFilter.equals(item.getStatus()))
                // The admin UI uses 0 for the "global only" option because a select cannot
                // distinguish an unset value from a deliberate null choice.
                .filter(item -> siteId == null
                        || (siteId == 0 ? item.getSiteId() == null : siteId.equals(item.getSiteId())))
                .filter(item -> keywordFilter == null
                        || keywordFilter.isEmpty()
                        || item.getTitle().toLowerCase(Locale.ROOT).contains(keywordFilter)
                        || item.getContent().toLowerCase(Locale.ROOT).contains(keywordFilter))
                .map(NotificationService::toAdminResponse)
                .toList();
    }

    @Transactional
    public AdminNotificationResponse create(AdminNotificationRequest request) {
        SiteNotificationEntity notification = new SiteNotificationEntity();
        apply(notification, request);
        return toAdminResponse(notifications.saveAndFlush(notification));
    }

    @Transactional
    public AdminNotificationResponse update(Long id, AdminNotificationRequest request) {
        SiteNotificationEntity notification = requireNotification(id);
        apply(notification, request);
        return toAdminResponse(notifications.saveAndFlush(notification));
    }

    @Transactional
    public AdminNotificationResponse updateStatus(Long id, NotificationStatusRequest request) {
        SiteNotificationEntity notification = requireNotification(id);
        setStatus(notification, normalizeStatus(request.status(), false));
        return toAdminResponse(notifications.saveAndFlush(notification));
    }

    @Transactional
    public void delete(Long id) {
        SiteNotificationEntity notification = requireNotification(id);
        notifications.delete(notification);
        notifications.flush();
    }

    public List<UserNotificationResponse> userList(CurrentUser user, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        List<SiteNotificationEntity> visible = notifications.findVisible(
                user.siteId(),
                LocalDateTime.now(),
                SiteNotificationEntity.STATUS_PUBLISHED,
                PageRequest.of(0, normalizedLimit));
        if (visible.isEmpty()) {
            return List.of();
        }

        List<Long> ids = visible.stream().map(SiteNotificationEntity::getId).toList();
        Set<Long> readIds = new HashSet<>();
        reads.findByUserIdAndNotificationIdIn(user.userId(), ids)
                .forEach(item -> readIds.add(item.getNotificationId()));

        return visible.stream()
                .map(item -> new UserNotificationResponse(
                        item.getId(),
                        item.getTitle(),
                        item.getContent(),
                        item.getPriority(),
                        item.getPublishedAt(),
                        readIds.contains(item.getId())))
                .toList();
    }

    public UnreadCountResponse unreadCount(CurrentUser user) {
        long count = notifications.countUnread(
                user.userId(),
                user.siteId(),
                LocalDateTime.now(),
                SiteNotificationEntity.STATUS_PUBLISHED);
        return new UnreadCountResponse(count);
    }

    @Transactional
    public void markRead(CurrentUser user, Long notificationId) {
        LocalDateTime now = LocalDateTime.now();
        notifications.findVisibleById(
                        notificationId,
                        user.siteId(),
                        now,
                        SiteNotificationEntity.STATUS_PUBLISHED)
                .orElseThrow(() -> new NotFoundException(
                        "Notification %d was not found".formatted(notificationId)));

        if (!reads.existsByNotificationIdAndUserId(notificationId, user.userId())) {
            reads.save(new UserNotificationReadEntity(notificationId, user.userId(), now));
        }
    }

    @Transactional
    public ReadAllResponse markAllRead(CurrentUser user) {
        LocalDateTime now = LocalDateTime.now();
        List<SiteNotificationEntity> visible = notifications.findAllVisible(
                user.siteId(),
                now,
                SiteNotificationEntity.STATUS_PUBLISHED);
        if (visible.isEmpty()) {
            return new ReadAllResponse(0);
        }

        List<Long> ids = visible.stream().map(SiteNotificationEntity::getId).toList();
        Set<Long> readIds = new HashSet<>();
        reads.findByUserIdAndNotificationIdIn(user.userId(), ids)
                .forEach(item -> readIds.add(item.getNotificationId()));

        List<UserNotificationReadEntity> unread = visible.stream()
                .filter(item -> !readIds.contains(item.getId()))
                .map(item -> new UserNotificationReadEntity(item.getId(), user.userId(), now))
                .toList();
        reads.saveAll(unread);
        return new ReadAllResponse(unread.size());
    }

    private void apply(SiteNotificationEntity notification, AdminNotificationRequest request) {
        if (request.siteId() != null && !sites.existsById(request.siteId())) {
            throw new InvalidRequestException(
                    "siteId %d does not exist".formatted(request.siteId()));
        }

        notification.setTitle(requiredText(request.title(), MAX_TITLE_LENGTH, "title"));
        notification.setContent(requiredText(request.content(), MAX_CONTENT_LENGTH, "content"));
        notification.setPriority(normalizePriority(request.priority()));
        notification.setSiteId(request.siteId());
        setStatus(notification, normalizeStatus(request.status(), true));
    }

    private SiteNotificationEntity requireNotification(Long id) {
        return notifications.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Notification %d was not found".formatted(id)));
    }

    private static void setStatus(SiteNotificationEntity notification, String status) {
        boolean becomingPublished = SiteNotificationEntity.STATUS_PUBLISHED.equals(status)
                && !SiteNotificationEntity.STATUS_PUBLISHED.equals(notification.getStatus());
        notification.setStatus(status);
        if (SiteNotificationEntity.STATUS_PUBLISHED.equals(status)) {
            if (notification.getPublishedAt() == null || becomingPublished) {
                notification.setPublishedAt(LocalDateTime.now());
            }
        }
    }

    private static String requiredText(String value, int maxLength, String field) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty()) {
            throw new InvalidRequestException("%s must not be blank".formatted(field));
        }
        if (normalized.length() > maxLength) {
            throw new InvalidRequestException(
                    "%s must be at most %d characters".formatted(field, maxLength));
        }
        return normalized;
    }

    private static String optionalStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return normalizeStatus(status, false);
    }

    private static String normalizeStatus(String status, boolean defaultPublished) {
        if (status == null || status.isBlank()) {
            if (defaultPublished) {
                return SiteNotificationEntity.STATUS_PUBLISHED;
            }
            throw new InvalidRequestException("status must not be blank");
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!SiteNotificationEntity.STATUS_DRAFT.equals(normalized)
                && !SiteNotificationEntity.STATUS_PUBLISHED.equals(normalized)) {
            throw new InvalidRequestException("status must be DRAFT or PUBLISHED");
        }
        return normalized;
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return SiteNotificationEntity.PRIORITY_NORMAL;
        }
        String normalized = priority.strip().toUpperCase(Locale.ROOT);
        if (!SiteNotificationEntity.PRIORITY_NORMAL.equals(normalized)
                && !SiteNotificationEntity.PRIORITY_HIGH.equals(normalized)) {
            throw new InvalidRequestException("priority must be NORMAL or HIGH");
        }
        return normalized;
    }

    private static AdminNotificationResponse toAdminResponse(SiteNotificationEntity notification) {
        return new AdminNotificationResponse(
                notification.getId(),
                notification.getTitle(),
                notification.getContent(),
                notification.getPriority(),
                notification.getStatus(),
                notification.getSiteId(),
                notification.getPublishedAt(),
                notification.getCreatedAt(),
                notification.getUpdatedAt());
    }
}

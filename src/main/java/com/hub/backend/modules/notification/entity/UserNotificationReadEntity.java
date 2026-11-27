package com.hub.backend.modules.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Per-account read marker for a published notification.
 */
@Entity
@Table(
        name = "user_notification_reads",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_notification_reads_notification_user",
                columnNames = {"notification_id", "user_id"}))
public class UserNotificationReadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    protected UserNotificationReadEntity() {
    }

    public UserNotificationReadEntity(Long notificationId, Long userId, LocalDateTime readAt) {
        this.notificationId = notificationId;
        this.userId = userId;
        this.readAt = readAt;
    }

    public Long getId() {
        return id;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }
}

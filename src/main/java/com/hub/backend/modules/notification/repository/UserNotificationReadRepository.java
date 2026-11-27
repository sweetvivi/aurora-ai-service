package com.hub.backend.modules.notification.repository;

import com.hub.backend.modules.notification.entity.UserNotificationReadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UserNotificationReadRepository
        extends JpaRepository<UserNotificationReadEntity, Long> {

    boolean existsByNotificationIdAndUserId(Long notificationId, Long userId);

    List<UserNotificationReadEntity> findByUserIdAndNotificationIdIn(
            Long userId, Collection<Long> notificationIds);
}

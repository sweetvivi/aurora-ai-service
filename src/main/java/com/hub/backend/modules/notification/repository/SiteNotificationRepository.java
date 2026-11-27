package com.hub.backend.modules.notification.repository;

import com.hub.backend.modules.notification.entity.SiteNotificationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SiteNotificationRepository extends JpaRepository<SiteNotificationEntity, Long> {

    List<SiteNotificationEntity> findAllByOrderByCreatedAtDescIdDesc();

    @Query("""
            select notification
              from SiteNotificationEntity notification
             where notification.status = :status
               and notification.publishedAt is not null
               and notification.publishedAt <= :now
               and (notification.siteId = :siteId or notification.siteId is null)
             order by notification.publishedAt desc, notification.id desc
            """)
    List<SiteNotificationEntity> findVisible(@Param("siteId") Long siteId,
                                             @Param("now") LocalDateTime now,
                                             @Param("status") String status,
                                             Pageable pageable);

    @Query("""
            select notification
              from SiteNotificationEntity notification
             where notification.status = :status
               and notification.publishedAt is not null
               and notification.publishedAt <= :now
               and (notification.siteId = :siteId or notification.siteId is null)
             order by notification.publishedAt desc, notification.id desc
            """)
    List<SiteNotificationEntity> findAllVisible(@Param("siteId") Long siteId,
                                                @Param("now") LocalDateTime now,
                                                @Param("status") String status);

    @Query("""
            select notification
              from SiteNotificationEntity notification
             where notification.id = :id
               and notification.status = :status
               and notification.publishedAt is not null
               and notification.publishedAt <= :now
               and (notification.siteId = :siteId or notification.siteId is null)
            """)
    Optional<SiteNotificationEntity> findVisibleById(@Param("id") Long id,
                                                     @Param("siteId") Long siteId,
                                                     @Param("now") LocalDateTime now,
                                                     @Param("status") String status);

    @Query("""
            select count(notification)
              from SiteNotificationEntity notification
             where notification.status = :status
               and notification.publishedAt is not null
               and notification.publishedAt <= :now
               and (notification.siteId = :siteId or notification.siteId is null)
               and not exists (
                   select read.id
                     from UserNotificationReadEntity read
                    where read.notificationId = notification.id
                      and read.userId = :userId
               )
            """)
    long countUnread(@Param("userId") Long userId,
                     @Param("siteId") Long siteId,
                     @Param("now") LocalDateTime now,
                     @Param("status") String status);
}

package com.hub.backend.modules.aicanvas.repository;

import com.hub.backend.modules.aicanvas.entity.AiCanvasTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiCanvasTaskRepository extends JpaRepository<AiCanvasTaskEntity, String> {

    Optional<AiCanvasTaskEntity> findByTaskIdAndUserId(String taskId, Long userId);

    List<AiCanvasTaskEntity> findByUserIdAndTaskTypeOrderByCreatedAtDesc(
            Long userId, String taskType);

    @Query("""
            select t from AiCanvasTaskEntity t
            where t.taskType = :taskType
              and (:status is null or t.status = :status)
              and (:keyword is null
                   or lower(coalesce(t.taskId, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(t.prompt, '')) like lower(concat('%', :keyword, '%'))
                   or exists (
                       select u.id from UserAccountEntity u
                       where u.id = t.userId
                         and lower(coalesce(u.username, '')) like lower(concat('%', :keyword, '%'))
                   ))
            """)
    Page<AiCanvasTaskEntity> searchAdminTasks(@Param("taskType") String taskType,
                                              @Param("keyword") String keyword,
                                              @Param("status") String status,
                                              Pageable pageable);
}

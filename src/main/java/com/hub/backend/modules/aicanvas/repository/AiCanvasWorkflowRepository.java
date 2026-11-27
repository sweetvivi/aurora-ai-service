package com.hub.backend.modules.aicanvas.repository;

import com.hub.backend.modules.aicanvas.entity.AiCanvasWorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiCanvasWorkflowRepository extends JpaRepository<AiCanvasWorkflowEntity, String> {

    List<AiCanvasWorkflowEntity> findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long userId, String status);

    Optional<AiCanvasWorkflowEntity> findByIdAndUserIdAndStatus(
            String id, Long userId, String status);

    List<AiCanvasWorkflowEntity> findByStatusOrderByCreatedAtAsc(String status);

    Optional<AiCanvasWorkflowEntity> findByIdAndStatus(String id, String status);

    boolean existsByUserIdAndCoverKeyAndStatus(
            Long userId, String coverKey, String status);
}

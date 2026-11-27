package com.hub.backend.modules.aicanvas.repository;

import com.hub.backend.modules.aicanvas.entity.AiCanvasChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiCanvasChatSessionRepository extends JpaRepository<AiCanvasChatSessionEntity, String> {

    List<AiCanvasChatSessionEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(
            Long userId, String status);

    Optional<AiCanvasChatSessionEntity> findByIdAndUserIdAndStatus(
            String id, Long userId, String status);
}

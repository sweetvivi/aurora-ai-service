package com.hub.backend.modules.aicanvas.repository;

import com.hub.backend.modules.aicanvas.entity.AiCanvasChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiCanvasChatMessageRepository extends JpaRepository<AiCanvasChatMessageEntity, String> {

    List<AiCanvasChatMessageEntity> findBySessionIdOrderBySequenceNoAsc(String sessionId);

    long countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}

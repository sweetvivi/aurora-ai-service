package com.hub.backend.modules.aicanvas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_canvas_chat_sessions")
public class AiCanvasChatSessionEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AiCanvasChatSessionEntity() {
    }

    public AiCanvasChatSessionEntity(String id, Long userId, String title) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.messageCount = 0;
        this.status = STATUS_ACTIVE;
    }

    public void recordMessage() {
        this.messageCount++;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

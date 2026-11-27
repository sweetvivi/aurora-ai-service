package com.hub.backend.modules.aicanvas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_canvas_chat_messages")
public class AiCanvasChatMessageEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "refs_json", nullable = false, columnDefinition = "LONGTEXT")
    private String refsJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AiCanvasChatMessageEntity() {
    }

    public AiCanvasChatMessageEntity(String id, String sessionId, int sequenceNo, String role,
                                     String content, String refsJson) {
        this.id = id;
        this.sessionId = sessionId;
        this.sequenceNo = sequenceNo;
        this.role = role;
        this.content = content;
        this.refsJson = refsJson;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getRefsJson() {
        return refsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

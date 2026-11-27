package com.hub.backend.modules.smartedit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One smart-editing board owned by an account.
 *
 * <p>The canvas and message history are frontend-defined documents. They are persisted as JSON text
 * so a new message type or canvas field does not require a database migration.</p>
 */
@Entity
@Table(name = "smart_edit_records")
public class SmartEditRecordEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEMO = "DEMO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null only for public demonstration records. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "canvas_json", nullable = false, columnDefinition = "LONGTEXT")
    private String canvasJson;

    @Column(name = "messages_json", nullable = false, columnDefinition = "LONGTEXT")
    private String messagesJson;

    @Column(name = "cover", length = 1024)
    private String cover;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SmartEditRecordEntity() {
    }

    public SmartEditRecordEntity(Long userId, String title, String canvasJson, String messagesJson,
                                 String cover, String status) {
        this.userId = userId;
        this.title = title;
        this.canvasJson = canvasJson;
        this.messagesJson = messagesJson;
        this.cover = cover;
        this.status = status == null || status.isBlank() ? STATUS_ACTIVE : status;
    }

    public void updateCanvas(String canvasJson, String cover) {
        this.canvasJson = canvasJson;
        this.cover = cover;
    }

    public void updateMessages(String messagesJson) {
        this.messagesJson = messagesJson;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getCanvasJson() {
        return canvasJson;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public String getCover() {
        return cover;
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

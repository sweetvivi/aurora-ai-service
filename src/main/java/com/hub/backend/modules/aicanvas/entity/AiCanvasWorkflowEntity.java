package com.hub.backend.modules.aicanvas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_canvas_workflows")
public class AiCanvasWorkflowEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PUBLIC = "PUBLIC";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "nodes_json", nullable = false, columnDefinition = "LONGTEXT")
    private String nodesJson;

    @Column(name = "groups_json", nullable = false, columnDefinition = "LONGTEXT")
    private String groupsJson;

    @Column(name = "viewport_json", nullable = false, columnDefinition = "LONGTEXT")
    private String viewportJson;

    @Column(name = "cover_key", length = 64)
    private String coverKey;

    @Column(name = "cover_url", length = 1024)
    private String coverUrl;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AiCanvasWorkflowEntity() {
    }

    public AiCanvasWorkflowEntity(String id, Long userId, String title, String description,
                                  String nodesJson, String groupsJson, String viewportJson,
                                  String coverKey, String coverUrl, String status) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.nodesJson = nodesJson;
        this.groupsJson = groupsJson;
        this.viewportJson = viewportJson;
        this.coverKey = coverKey;
        this.coverUrl = coverUrl;
        this.status = status;
    }

    public void updateDocument(String title, String description, String nodesJson, String groupsJson,
                               String viewportJson) {
        this.title = title;
        this.description = description;
        this.nodesJson = nodesJson;
        this.groupsJson = groupsJson;
        this.viewportJson = viewportJson;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void updateCover(String coverKey, String coverUrl) {
        this.coverKey = coverKey;
        this.coverUrl = coverUrl;
    }

    public void clearCover(String coverKey) {
        if (coverKey != null && coverKey.equals(this.coverKey)) {
            this.coverKey = null;
            this.coverUrl = null;
        }
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

    public String getDescription() {
        return description;
    }

    public String getNodesJson() {
        return nodesJson;
    }

    public String getGroupsJson() {
        return groupsJson;
    }

    public String getViewportJson() {
        return viewportJson;
    }

    public String getCoverKey() {
        return coverKey;
    }

    public String getCoverUrl() {
        return coverUrl;
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

package com.hub.backend.modules.aicanvas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_canvas_assets")
public class AiCanvasAssetEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @Column(name = "asset_key", nullable = false, length = 64)
    private String key;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Blob key when this material owns its bytes. {@code null} for a material that only points at
     * an attachment ({@link #sourceFileId}) — see {@code V79__canvas_asset_source_file.sql}.
     */
    @Column(name = "storage_key", length = 768)
    private String storageKey;

    /**
     * Attachment this material refers to instead of copying it. Such a row owns no bytes, so it is
     * left out of the canvas quota: the bytes are already counted against the attachment quota.
     */
    @Column(name = "source_file_id")
    private Long sourceFileId;

    @Column(name = "name", nullable = false, length = 512)
    private String name;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "asset_type", nullable = false, length = 32)
    private String type;

    @Column(name = "mime_type", nullable = false, length = 255)
    private String mimeType;

    @Column(name = "extension", nullable = false, length = 32)
    private String extension;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "prompt", length = 4000)
    private String prompt;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AiCanvasAssetEntity() {
    }

    public AiCanvasAssetEntity(String key, Long userId, String storageKey, String name, String category,
                               String type, String mimeType, String extension, long sizeBytes,
                               String prompt, String model) {
        this.key = key;
        this.userId = userId;
        this.storageKey = storageKey;
        this.name = name;
        this.category = category;
        this.type = type;
        this.mimeType = mimeType;
        this.extension = extension;
        this.sizeBytes = sizeBytes;
        this.prompt = prompt;
        this.model = model;
        this.status = STATUS_ACTIVE;
    }

    /**
     * A material that only references an attachment. {@code sizeBytes} is the attachment's size,
     * kept for display; no blob is copied.
     */
    public static AiCanvasAssetEntity referencing(String key, Long userId, Long sourceFileId,
                                                 String name, String category, String type,
                                                 String mimeType, String extension, long sizeBytes,
                                                 String prompt, String model) {
        AiCanvasAssetEntity asset = new AiCanvasAssetEntity(key, userId, null, name, category, type,
                mimeType, extension, sizeBytes, prompt, model);
        asset.sourceFileId = sourceFileId;
        return asset;
    }

    /** {@code true} when the bytes live in {@code uploaded_files} rather than in this material. */
    public boolean referencesFile() {
        return sourceFileId != null;
    }

    public void markDeleted() {
        this.status = STATUS_DELETED;
    }

    public String getKey() {
        return key;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Long getSourceFileId() {
        return sourceFileId;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getType() {
        return type;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getExtension() {
        return extension;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getModel() {
        return model;
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

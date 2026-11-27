package com.hub.backend.modules.file.entity;

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
 * Metadata of one uploaded attachment.
 *
 * <p>The bytes live in the blob store keyed by {@code storageKey}; this table only keeps the
 * metadata, so a large upload never lands in the database (see {@code docs/project-audit.md} on
 * keeping {@code extracted_text} out of the main store).</p>
 */
@Entity
@Table(name = "uploaded_files")
public class UploadedFileEntity {

    /** The attachment is available to its owner. */
    public static final String STATUS_UPLOADED = "UPLOADED";

    /** The attachment was removed by its owner. */
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "extension", length = 32)
    private String extension;

    @Column(name = "asset_type", nullable = false, length = 32)
    private String assetType;

    /**
     * Prompt that produced this picture, when the producing feature had one.
     *
     * <p>Works are listed across features since V88, so a card can no longer rely on its own
     * page's local prompt cache — the prompt has to travel with the row. Nullable: uploads,
     * documents and rows written before V88 have nothing meaningful to record.</p>
     */
    @Column(name = "prompt", length = 1000)
    private String prompt;

    /**
     * Feature that produced this attachment ({@code image_generation}, {@code painting}, …).
     * Nullable on purpose: rows written before V77 keep {@code null} and belong to no work list.
     */
    @Column(name = "source", length = 32)
    private String source;

    /**
     * Image model that produced this picture, as the upstream key (e.g. {@code gpt-image-1}).
     *
     * <p>Before V91 the model name only lived in the browser's localStorage, so a cleared cache,
     * a different device or any older row left the work card with nothing to render and it fell
     * back to 「未知模型」. Nullable: pre-V91 rows and non-generated attachments have none.</p>
     */
    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // Explicit column definition: the schema stores this as CHAR(64), while a plain String mapping
    // produces VARCHAR and would fail schema validation.
    @Column(name = "checksum_sha256", columnDefinition = "CHAR(64)")
    private String checksumSha256;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_UPLOADED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UploadedFileEntity() {
    }

    public UploadedFileEntity(Long userId, Long conversationId, String storageKey, String originalName,
                              String mimeType, String extension, FileAssetType assetType,
                              long sizeBytes, String checksumSha256) {
        this(userId, conversationId, storageKey, originalName, mimeType, extension, assetType, null,
                sizeBytes, checksumSha256);
    }

    public UploadedFileEntity(Long userId, Long conversationId, String storageKey, String originalName,
                              String mimeType, String extension, FileAssetType assetType,
                              FileSource source, long sizeBytes, String checksumSha256) {
        this(userId, conversationId, storageKey, originalName, mimeType, extension, assetType,
                source, sizeBytes, checksumSha256, null);
    }

    public UploadedFileEntity(Long userId, Long conversationId, String storageKey, String originalName,
                              String mimeType, String extension, FileAssetType assetType,
                              FileSource source, long sizeBytes, String checksumSha256, String prompt) {
        this(userId, conversationId, storageKey, originalName, mimeType, extension, assetType,
                source, sizeBytes, checksumSha256, prompt, null);
    }

    /**
     * Full form: also records which image model produced the bytes (V91).
     *
     * <p>The model is display material for work cards, same as the prompt — never let an
     * over-long or oddly formatted key fail an insert whose bytes are already stored.</p>
     */
    public UploadedFileEntity(Long userId, Long conversationId, String storageKey, String originalName,
                              String mimeType, String extension, FileAssetType assetType,
                              FileSource source, long sizeBytes, String checksumSha256, String prompt,
                              String model) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.extension = extension;
        this.assetType = assetType.value();
        this.source = source == null ? null : source.value();
        this.prompt = truncatePrompt(prompt);
        this.model = truncateModel(model);
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.status = STATUS_UPLOADED;
    }

    /**
     * Prompt text is display material, not a contract: cap it at the column width instead of
     * letting an unusually long request fail the insert after the bytes were already stored.
     */
    private static String truncatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String stripped = prompt.strip();
        return stripped.length() > 1000 ? stripped.substring(0, 1000) : stripped;
    }

    /** Same reasoning as {@link #truncatePrompt}: cap to the column width, blanks become null. */
    private static String truncateModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String stripped = model.strip();
        return stripped.length() > 64 ? stripped.substring(0, 64) : stripped;
    }

    /** Marks the attachment as removed; the blob is deleted separately. */
    public void markDeleted() {        this.status = STATUS_DELETED;
    }

    public void attachToConversation(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getExtension() {
        return extension;
    }

    public FileAssetType getAssetType() {
        return FileAssetType.fromValue(assetType);
    }

    /** Prompt that produced this picture, or {@code null} when the producing feature had none. */
    public String getPrompt() {
        return prompt;
    }

    /** Image model that produced this picture, or {@code null} for pre-V91 rows and uploads. */
    public String getModel() {
        return model;
    }

    /** Feature that produced this attachment, or {@code null} for rows written before V77. */
    public FileSource getSource() {
        return FileSource.fromValueOrNull(source);
    }

    /** Raw column value; {@code null} when unset. Used by finders and API mapping. */
    public String getSourceValue() {
        return source;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
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

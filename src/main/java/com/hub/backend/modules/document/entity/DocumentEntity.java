package com.hub.backend.modules.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A document opened in the 「文档处理」 workspace.
 *
 * <p>The bytes themselves stay in {@code uploaded_files}: uploads go through
 * {@code FileService} so the extension / MIME allow list, the per-account quota and the
 * SHA-256 checksum are the ones already in force everywhere else. This row only records
 * what the workspace needs to answer a tool click without re-parsing the file every time.</p>
 *
 * <p>{@code content} holds the extracted plain text. It is written once, on upload, by
 * {@code DocumentTextReader}; no request body is ever stored here verbatim.</p>
 */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    /** Usable: text has been extracted. */
    public static final String STATUS_READY = "READY";
    /** Soft deleted, hidden from every list. */
    public static final String STATUS_DELETED = "DELETED";

    /** Uploaded by the account. */
    public static final String SOURCE_UPLOAD = "UPLOAD";
    /** One of the built-in samples on the page, no file behind it. */
    public static final String SOURCE_SAMPLE = "SAMPLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "extension", nullable = false, length = 16)
    private String extension;

    /** Page level format label: {@code PDF} / {@code Word} / {@code Excel} / {@code TXT}. */
    @Column(name = "format", nullable = false, length = 16)
    private String format;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Only known for PDF; {@code null} for the other formats. */
    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Lob
    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_UPLOAD;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_READY;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DocumentEntity() {
        // for JPA
    }

    public DocumentEntity(Long userId,
                          Long siteId,
                          Long fileId,
                          String originalName,
                          String extension,
                          String format,
                          long sizeBytes,
                          Integer pageCount,
                          String content,
                          String source) {
        this.userId = userId;
        this.siteId = siteId;
        this.fileId = fileId;
        this.originalName = originalName;
        this.extension = extension;
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.pageCount = pageCount;
        this.content = content;
        this.charCount = content == null ? 0 : content.codePointCount(0, content.length());
        this.source = source == null ? SOURCE_UPLOAD : source;
    }

    public void markDeleted() {
        this.status = STATUS_DELETED;
    }

    public boolean isDeleted() {
        return STATUS_DELETED.equals(status);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public Long getFileId() {
        return fileId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getExtension() {
        return extension;
    }

    public String getFormat() {
        return format;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public int getCharCount() {
        return charCount;
    }

    public String getContent() {
        return content;
    }

    public String getSource() {
        return source;
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

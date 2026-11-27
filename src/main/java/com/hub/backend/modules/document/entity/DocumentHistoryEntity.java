package com.hub.backend.modules.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One row per tool click on the 「文档处理」 page.
 *
 * <p>This is a result snapshot, not a job queue: it stores what the user actually saw, which is
 * what makes 「查处理历史」 answerable after a refresh. The document name is copied in rather than
 * joined, so deleting a document does not erase the record of having processed it.</p>
 *
 * <p>Neither {@code documentId} nor {@code fileId} carries a foreign key on purpose — see
 * {@code V101__document_action_history.sql}.</p>
 */
@Entity
@Table(name = "document_action_history")
public class DocumentHistoryEntity {

    /** The tool ran and produced a result. */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** The tool failed; {@code errorMessage} says why. */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "document_name", nullable = false, length = 512)
    private String documentName;

    @Column(name = "document_format", nullable = false, length = 16)
    private String documentFormat = "";

    @Column(name = "action", nullable = false, length = 48)
    private String action;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    @Column(name = "options_json", length = 1024)
    private String optionsJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_SUCCESS;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Lob
    @Column(name = "result_text", columnDefinition = "LONGTEXT")
    private String resultText;

    /**
     * Character count of {@link #resultText}, kept as its own column so the list endpoint can show
     * the size without reading the LONGTEXT body.
     */
    @Column(name = "result_chars", nullable = false)
    private int resultChars;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_format", length = 16)
    private String fileFormat;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getDocumentFormat() {
        return documentFormat;
    }

    public void setDocumentFormat(String documentFormat) {
        this.documentFormat = documentFormat;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(String optionsJson) {
        this.optionsJson = optionsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getResultText() {
        return resultText;
    }

    public void setResultText(String resultText) {
        this.resultText = resultText;
        this.resultChars = resultText == null ? 0 : resultText.length();
    }

    public int getResultChars() {
        return resultChars;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

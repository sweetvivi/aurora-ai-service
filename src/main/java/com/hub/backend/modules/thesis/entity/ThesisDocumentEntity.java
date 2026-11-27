package com.hub.backend.modules.thesis.entity;

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
 * One thesis draft owned by one account.
 *
 * <p>The outline is stored as a JSON document rather than a node table: the editor rewrites the
 * whole tree on every save (rename, insert, delete) and never queries a single node in isolation,
 * so a JSON column keeps the write atomic and the schema small. {@code outlineJson} is written by
 * {@code ThesisService} after it has normalised the tree, never straight from a request body.</p>
 */
@Entity
@Table(name = "thesis_documents")
public class ThesisDocumentEntity {

    /** Outline only: the free tier stops here. */
    public static final String STATUS_OUTLINE = "OUTLINE";
    /** A full text generation is queued or running. */
    public static final String STATUS_GENERATING = "GENERATING";
    /** Full text delivered. */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** Soft deleted, hidden from every list. */
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "tier", nullable = false, length = 32)
    private String tier;

    @Column(name = "education_level", length = 32)
    private String educationLevel;

    @Column(name = "paper_type", length = 32)
    private String paperType;

    @Column(name = "outline_depth", nullable = false)
    private int outlineDepth;

    @Column(name = "discipline", nullable = false, length = 64)
    private String discipline;

    @Column(name = "major", nullable = false, length = 128)
    private String major;

    @Column(name = "target_words", nullable = false)
    private int targetWords;

    @Column(name = "reference_enabled", nullable = false)
    private boolean referenceEnabled;

    @Column(name = "attachments_enabled", nullable = false)
    private boolean attachmentsEnabled;

    @Column(name = "data_model_enabled", nullable = false)
    private boolean dataModelEnabled;

    @Column(name = "outline_json", nullable = false, columnDefinition = "LONGTEXT")
    private String outlineJson;

    @Column(name = "outline_version", nullable = false)
    private int outlineVersion;

    /**
     * The generated full text, stored as the exact response body the client received.
     *
     * <p>Same reasoning as {@code outlineJson}: chapters are read as a whole, never queried
     * individually, so a JSON column beats a section table. Null until the user has generated the
     * body — an outline-only draft has nothing to show in the reader.</p>
     */
    @Column(name = "full_text_json", columnDefinition = "LONGTEXT")
    private String fullTextJson;

    @Column(name = "full_text_model", length = 128)
    private String fullTextModel;

    @Column(name = "full_text_word_count", nullable = false)
    private int fullTextWordCount;

    @Column(name = "full_text_generated_at")
    private LocalDateTime fullTextGeneratedAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ThesisDocumentEntity() {
    }

    public ThesisDocumentEntity(Long userId, Long siteId, String topic, String tier, String discipline,
                                String major, int targetWords, boolean referenceEnabled,
                                boolean attachmentsEnabled, boolean dataModelEnabled,
                                String outlineJson, String status) {
        this(userId, siteId, topic, tier, null, null, 3, discipline, major, targetWords,
                referenceEnabled, attachmentsEnabled, dataModelEnabled, outlineJson, status);
    }

    public ThesisDocumentEntity(Long userId, Long siteId, String topic, String tier,
                                String educationLevel, String paperType, int outlineDepth,
                                String discipline, String major, int targetWords,
                                boolean referenceEnabled, boolean attachmentsEnabled,
                                boolean dataModelEnabled, String outlineJson, String status) {
        this.userId = userId;
        this.siteId = siteId;
        this.topic = topic;
        this.tier = tier;
        this.educationLevel = educationLevel;
        this.paperType = paperType;
        this.outlineDepth = outlineDepth;
        this.discipline = discipline;
        this.major = major;
        this.targetWords = targetWords;
        this.referenceEnabled = referenceEnabled;
        this.attachmentsEnabled = attachmentsEnabled;
        this.dataModelEnabled = dataModelEnabled;
        this.outlineJson = outlineJson;
        this.outlineVersion = 1;
        this.status = status;
    }

    /**
     * Replaces the whole outline tree. Bumping the version lets a client detect that somebody else
     * (or another tab) saved in the meantime.
     */
    public void updateOutline(String outlineJson, int outlineDepth) {
        this.outlineJson = outlineJson;
        this.outlineDepth = outlineDepth;
        this.outlineVersion = this.outlineVersion + 1;
        if (STATUS_DELETED.equals(this.status)) {
            this.status = STATUS_OUTLINE;
        }
    }

    public void updateForm(String topic, String discipline, String major, int targetWords,
                           boolean referenceEnabled, boolean attachmentsEnabled, boolean dataModelEnabled) {
        this.topic = topic;
        this.discipline = discipline;
        this.major = major;
        this.targetWords = targetWords;
        this.referenceEnabled = referenceEnabled;
        this.attachmentsEnabled = attachmentsEnabled;
        this.dataModelEnabled = dataModelEnabled;
    }

    public void changeStatus(String status) {
        this.status = status;
    }

    /** Stores a freshly generated body and marks the draft as delivered. */
    public void saveFullText(String fullTextJson, String model, int wordCount) {
        this.fullTextJson = fullTextJson;
        this.fullTextModel = model;
        this.fullTextWordCount = wordCount;
        this.fullTextGeneratedAt = LocalDateTime.now();
        this.status = STATUS_COMPLETED;
    }

    public void markDeleted() {
        this.status = STATUS_DELETED;
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

    public String getTopic() {
        return topic;
    }

    public String getTier() {
        return tier;
    }

    public String getEducationLevel() {
        return educationLevel;
    }

    public String getPaperType() {
        return paperType;
    }

    public int getOutlineDepth() {
        return outlineDepth;
    }

    public String getDiscipline() {
        return discipline;
    }

    public String getMajor() {
        return major;
    }

    public int getTargetWords() {
        return targetWords;
    }

    public boolean isReferenceEnabled() {
        return referenceEnabled;
    }

    public boolean isAttachmentsEnabled() {
        return attachmentsEnabled;
    }

    public boolean isDataModelEnabled() {
        return dataModelEnabled;
    }

    public String getOutlineJson() {
        return outlineJson;
    }

    public int getOutlineVersion() {
        return outlineVersion;
    }

    public String getFullTextJson() {
        return fullTextJson;
    }

    public String getFullTextModel() {
        return fullTextModel;
    }

    public int getFullTextWordCount() {
        return fullTextWordCount;
    }

    public LocalDateTime getFullTextGeneratedAt() {
        return fullTextGeneratedAt;
    }

    /** Whether a generated body is stored — independent of the status string. */
    public boolean hasFullText() {
        return fullTextJson != null && !fullTextJson.isBlank();
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

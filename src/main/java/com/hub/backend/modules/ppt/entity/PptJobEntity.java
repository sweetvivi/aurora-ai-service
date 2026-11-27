package com.hub.backend.modules.ppt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "ppt_jobs")
public class PptJobEntity {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_PARSING = "parsing";
    public static final String STATUS_SEARCHING = "searching";
    public static final String STATUS_OUTLINING = "outlining";
    public static final String STATUS_WRITING = "writing";
    public static final String STATUS_IMAGING = "imaging";
    public static final String STATUS_RENDERING = "rendering";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    /**
     * 占用并发额度的状态：还没开始跑的（queued）和正在跑的。
     *
     * <p>建单时的准入判断与恢复扫描都读这一份，避免两处各写一遍状态名后漂移。</p>
     */
    public static final List<String> IN_FLIGHT_STATUSES = List.of(
            STATUS_QUEUED, STATUS_PARSING, STATUS_SEARCHING, STATUS_OUTLINING,
            STATUS_WRITING, STATUS_IMAGING, STATUS_RENDERING);

    /**
     * 已经领取、正在推进的状态（不含 queued）。
     *
     * <p>只有这些状态持有租约，所以「租约过期」这条判死规则只对它们成立：一个还在 queued 的行
     * 不应该被判失败，它应该被重新提交。</p>
     */
    public static final List<String> STARTED_STATUSES = List.of(
            STATUS_PARSING, STATUS_SEARCHING, STATUS_OUTLINING,
            STATUS_WRITING, STATUS_IMAGING, STATUS_RENDERING);


    @Id
    @Column(name = "job_id", nullable = false, length = 64)
    private String jobId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "message", length = 1024)
    private String message;

    @Column(name = "stage", length = 64)
    private String stage;

    @Column(name = "progress_msg", length = 1024)
    private String progressMsg;

    @Column(name = "topic", columnDefinition = "LONGTEXT")
    private String topic;

    @Column(name = "source_text", columnDefinition = "LONGTEXT")
    private String sourceText;

    @Column(name = "file_ids_json", columnDefinition = "LONGTEXT")
    private String fileIdsJson;

    @Column(name = "lang", length = 16)
    private String lang;

    @Column(name = "page_range", length = 32)
    private String pageRange;

    @Column(name = "detail", length = 64)
    private String detail;

    @Column(name = "scene", length = 64)
    private String scene;

    @Column(name = "tone", length = 64)
    private String tone;

    @Column(name = "text_model_id", length = 128)
    private String textModelId;

    @Column(name = "image_model_id", length = 128)
    private String imageModelId;

    @Column(name = "auto_img", nullable = false)
    private boolean autoImg;

    /** light | mid | heavy — illustration density when autoImg is on. */
    @Column(name = "img_density", nullable = false, length = 16)
    private String imgDensity = "mid";

    @Column(name = "web_search", nullable = false)
    private boolean webSearch;

    @Column(name = "template_id", length = 64)
    private String templateId;

    /** agent | template — product generate modes (beauty is runtime-only). */
    @Column(name = "generate_mode", nullable = false, length = 32)
    private String generateMode = "agent";

    @Column(name = "deck_template_id", length = 64)
    private String deckTemplateId;

    @Column(name = "aspect_ratio", nullable = false, length = 16)
    private String aspectRatio = "16:9";

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "estimated_points", nullable = false)
    private int estimatedPoints;

    @Column(name = "points_cost")
    private Integer pointsCost;

    @Column(name = "presenton_task_id", length = 128)
    private String presentonTaskId;

    @Column(name = "presenton_presentation_id", length = 1024)
    private String presentonPresentationId;

    @Column(name = "presenton_path", length = 1024)
    private String presentonPath;

    @Column(name = "storage_key", length = 768)
    private String storageKey;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "preview_urls_json", columnDefinition = "LONGTEXT")
    private String previewUrlsJson;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    /** 领取这条任务的 worker 标识（每进程一个），仅用于诊断与租约回收。 */
    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    /** 租约到期时间。推进进度时续期，过期即认为执行者已退出。 */
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected PptJobEntity() {
    }

    public PptJobEntity(String jobId, Long userId, String title) {
        this.jobId = jobId;
        this.userId = userId;
        this.title = title;
        this.status = STATUS_QUEUED;
        this.progress = 0;
        this.cancelled = false;
    }

    public void markProgress(String status, int progress, String message) {
        this.status = status;
        this.progress = Math.max(0, Math.min(progress, 100));
        this.message = message;
        this.progressMsg = message;
    }

    public void markStage(String stage, int progress, String message) {
        this.stage = stage;
        markProgress(this.status == null ? STATUS_RENDERING : this.status, progress, message);
    }

    public void markSucceeded(String storageKey, String fileName, Integer pageCount, int pointsCost) {
        this.status = STATUS_SUCCEEDED;
        this.progress = 100;
        this.message = "生成完成";
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.pageCount = pageCount;
        this.pointsCost = pointsCost;
        this.errorMessage = null;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = STATUS_FAILED;
        this.message = "生成失败";
        this.errorMessage = truncate(errorMessage, 2048);
        this.finishedAt = LocalDateTime.now();
    }

    public void markCancelled() {
        this.cancelled = true;
        this.status = STATUS_CANCELLED;
        this.message = "已取消";
        this.finishedAt = LocalDateTime.now();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public String getJobId() {
        return jobId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public String getMessage() {
        return message;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getProgressMsg() {
        return progressMsg;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public String getFileIdsJson() {
        return fileIdsJson;
    }

    public void setFileIdsJson(String fileIdsJson) {
        this.fileIdsJson = fileIdsJson;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getPageRange() {
        return pageRange;
    }

    public void setPageRange(String pageRange) {
        this.pageRange = pageRange;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getTone() {
        return tone;
    }

    public void setTone(String tone) {
        this.tone = tone;
    }

    public String getTextModelId() {
        return textModelId;
    }

    public void setTextModelId(String textModelId) {
        this.textModelId = textModelId;
    }

    public String getImageModelId() {
        return imageModelId;
    }

    public void setImageModelId(String imageModelId) {
        this.imageModelId = imageModelId;
    }

    public boolean isAutoImg() {
        return autoImg;
    }

    public void setAutoImg(boolean autoImg) {
        this.autoImg = autoImg;
    }

    public String getImgDensity() {
        return imgDensity;
    }

    public void setImgDensity(String imgDensity) {
        this.imgDensity = imgDensity == null || imgDensity.isBlank() ? "mid" : imgDensity.trim();
    }

    public boolean isWebSearch() {
        return webSearch;
    }

    public void setWebSearch(boolean webSearch) {
        this.webSearch = webSearch;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getGenerateMode() {
        return generateMode;
    }

    public void setGenerateMode(String generateMode) {
        this.generateMode = generateMode == null || generateMode.isBlank() ? "agent" : generateMode.trim();
    }

    public String getDeckTemplateId() {
        return deckTemplateId;
    }

    public void setDeckTemplateId(String deckTemplateId) {
        this.deckTemplateId = deckTemplateId;
    }

    public String getAspectRatio() {
        return aspectRatio;
    }

    public void setAspectRatio(String aspectRatio) {
        this.aspectRatio = aspectRatio == null || aspectRatio.isBlank() ? "16:9" : aspectRatio.trim();
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public int getEstimatedPoints() {
        return estimatedPoints;
    }

    public void setEstimatedPoints(int estimatedPoints) {
        this.estimatedPoints = estimatedPoints;
    }

    public Integer getPointsCost() {
        return pointsCost;
    }

    public String getPresentonTaskId() {
        return presentonTaskId;
    }

    public void setPresentonTaskId(String presentonTaskId) {
        this.presentonTaskId = presentonTaskId;
    }

    public String getPresentonPresentationId() {
        return presentonPresentationId;
    }

    public void setPresentonPresentationId(String presentonPresentationId) {
        this.presentonPresentationId = presentonPresentationId;
    }

    public String getPresentonPath() {
        return presentonPath;
    }

    public void setPresentonPath(String presentonPath) {
        this.presentonPath = presentonPath;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getPreviewUrlsJson() {
        return previewUrlsJson;
    }

    public void setPreviewUrlsJson(String previewUrlsJson) {
        this.previewUrlsJson = previewUrlsJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }
}

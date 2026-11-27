package com.hub.backend.modules.video.dto;

import com.hub.backend.modules.video.entity.AsyncTaskEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Client view of one video job.
 *
 * <p>Status values are lower case to match the contract the front end already documents
 * ({@code queued / running / succeeded / failed / cancelled}).</p>
 *
 * <p>Failure reason is {@code errorMessage} (short Chinese).</p>
 */
@Schema(name = "VideoTask", description = "视频生成任务")
public record VideoTaskResponse(
        @Schema(description = "任务号，提交时返回", example = "vt_9f2c1d") String taskId,
        @Schema(description = "任务状态：queued / running / succeeded / failed / cancelled",
                example = "running") String status,
        @Schema(description = "进度百分比 0-100", example = "42") int progress,
        @Schema(description = "模型 key", example = "seedance-2.0") String model,
        @Schema(description = "提示词") String prompt,
        @Schema(description = "生成模式：text2video / image2video / first-last-frame / reference2video",
                example = "image2video") String mode,
        @Schema(description = "画幅比例", example = "16:9") String aspectRatio,
        @Schema(description = "分辨率", example = "1080p") String resolution,
        @Schema(description = "时长（秒）", example = "5") Integer duration,
        @Schema(description = "首帧参考图附件 ID") Long referenceFileId,
        @Schema(description = "首帧参考图地址", example = "/api/v1/files/3/content") String referenceUrl,
        @Schema(description = "尾帧参考图附件 ID，仅首尾帧模式有值") Long lastFrameFileId,
        @Schema(description = "尾帧参考图地址", example = "/api/v1/files/4/content") String lastFrameUrl,
        @Schema(description = "参考图附件 ID 列表（reference2video）") List<Long> referenceFileIds,
        @Schema(description = "参考图地址列表（reference2video）") List<String> referenceUrls,
        @Schema(description = "生成结果的下载地址，仅成功的任务有值",
                example = "/api/v1/files/7/content") String videoUrl,
        @Schema(description = "结果附件 ID，仅成功的任务有值") Long fileId,
        @Schema(description = "失败原因（短中文），仅失败的任务有值") String errorMessage,
        @Schema(description = "提交时间") LocalDateTime createdAt,
        @Schema(description = "完成时间") LocalDateTime completedAt) {

    /** External spelling of an internal {@link AsyncTaskEntity} status. */
    public static String externalStatus(String internalStatus) {
        if (internalStatus == null) {
            return "queued";
        }
        return switch (internalStatus) {
            case AsyncTaskEntity.STATUS_PENDING -> "queued";
            case AsyncTaskEntity.STATUS_RUNNING -> "running";
            case AsyncTaskEntity.STATUS_SUCCEEDED -> "succeeded";
            case AsyncTaskEntity.STATUS_FAILED -> "failed";
            case AsyncTaskEntity.STATUS_CANCELLED -> "cancelled";
            default -> internalStatus.toLowerCase();
        };
    }
}

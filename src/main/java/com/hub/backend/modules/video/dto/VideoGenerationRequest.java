package com.hub.backend.modules.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A video generation request.
 *
 * <p>Validation is intentionally loose on the model-shaped fields: suppliers disagree on which
 * resolutions and durations they accept, and the catalogue is the place that decides. What is
 * checked here is only what is universally invalid.</p>
 */
@Schema(name = "VideoGenerationRequest", description = "视频生成请求")
public record VideoGenerationRequest(
        @Schema(description = "提示词，描述画面、镜头与动作", example = "橘猫慢慢转头看向镜头，电影感")
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 4000, message = "prompt must be at most 4000 characters")
        String prompt,

        @Schema(description = "视频模型 key，省略时使用服务端默认视频模型", example = "seedance-2.0")
        @Size(max = 128, message = "model must be at most 128 characters")
        String model,

        @Schema(description = "画面比例", example = "16:9")
        @Pattern(regexp = "[0-9]{1,2}:[0-9]{1,2}", message = "aspectRatio must look like 16:9")
        String aspectRatio,

        @Schema(description = "分辨率档位", example = "720p")
        @Pattern(regexp = "[0-9]{3,4}p", message = "resolution must look like 720p")
        String resolution,

        @Schema(description = "时长（秒）", example = "5")
        @Min(value = 1, message = "duration must be at least 1 second")
        @Max(value = 60, message = "duration must be at most 60 seconds")
        Integer duration,

        @Schema(description = "首帧参考图的附件 ID（先通过 /api/v1/files 上传）", example = "123")
        Long referenceFileId,

        @Schema(description = "尾帧参考图的附件 ID，仅首尾帧模式使用", example = "124")
        Long lastFrameFileId,

        @Schema(description = "参考图附件 ID 列表，仅参考图模式使用（1-14 张，与首尾帧互斥）；"
                + "实际可用张数由模型的 maxReferenceImages 能力决定",
                example = "[123,124]")
        @Size(max = 14, message = "at most 14 reference images are supported")
        List<Long> referenceFileIds,

        @Schema(description = "生成模式：text2video / image2video / first-last-frame / reference2video；"
                + "省略时按模型能力推断", example = "image2video")
        @Pattern(regexp = "text2video|image2video|first-last-frame|reference2video",
                message = "mode must be text2video, image2video, first-last-frame or reference2video")
        String mode,

        @Schema(description = "把任务挂到已有会话；省略时新建一个「AI视频：{prompt}」会话", example = "45")
        Long conversationId) {

    /** @return requested aspect ratio, or {@code null} when the service default applies */
    public String aspectRatioOrNull() {
        return blankToNull(aspectRatio);
    }

    public String resolutionOrNull() {
        return blankToNull(resolution);
    }

    public String modelOrNull() {
        return blankToNull(model);
    }

    public String modeOrNull() {
        return blankToNull(mode);
    }

    /** Non-null, de-duplicated reference file ids (order preserved). */
    public List<Long> referenceFileIdsOrEmpty() {
        if (referenceFileIds == null || referenceFileIds.isEmpty()) {
            return List.of();
        }
        return referenceFileIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}

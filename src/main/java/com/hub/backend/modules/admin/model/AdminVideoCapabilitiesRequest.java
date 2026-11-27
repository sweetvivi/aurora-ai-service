package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Video generation capability declaration written into {@code capabilities_json}.
 *
 * <p>Only meaningful for {@code VIDEO} models. Omitted on the parent request means "leave the
 * existing video fields alone"; sending this object is a <b>full replace</b> of the video keys
 * ({@code modes}/{@code aspectRatios}/{@code resolutions}/{@code durations}/
 * {@code maxReferenceImages}/{@code audio}) while preserving unrelated keys such as
 * {@code thinkingParameter}. Partial payloads like {@code {audio:true}} alone clear the other
 * lists to empty — the admin UI must always submit the complete form.</p>
 */
@Schema(name = "AdminVideoCapabilitiesRequest", description = "视频模型能力声明")
public record AdminVideoCapabilitiesRequest(
        @Schema(description = "生成模式：text2video / image2video / first-last-frame / reference2video",
                example = "[\"text2video\",\"image2video\",\"reference2video\"]")
        @Size(max = 8) List<@Size(max = 32) String> modes,

        @Schema(description = "画面比例", example = "[\"16:9\",\"9:16\",\"1:1\"]")
        @Size(max = 16) List<@Size(max = 16) String> aspectRatios,

        @Schema(description = "分辨率档位", example = "[\"480p\",\"720p\",\"1080p\"]")
        @Size(max = 8) List<@Size(max = 16) String> resolutions,

        @Schema(description = "可选时长（秒）。durationMode=range 时只需给上下限，"
                + "例如 [4,15] 表示 4 到 15 秒每个整数都能选；set 时逐个列出档位",
                example = "[5,10,15]")
        @Size(max = 64) List<@Min(1) @Max(60) Integer> durations,

        @Schema(description = "时长读法：range（区间）/ set（只认列出的档位）。"
                + "留空等同 range，兼容旧数据",
                example = "range")
        @Pattern(regexp = "range|set", message = "durationMode must be range or set")
        String durationMode,

        @Schema(description = "最多参考图数量：首尾帧通常为 2；reference2video 为可上传的参考图上限",
                example = "4")
        @Min(0) @Max(14) Integer maxReferenceImages,

        @Schema(description = "是否声明输出音频", example = "true")
        Boolean audio
) {
}

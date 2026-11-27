package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "AdminModelFallbackGroupRequest",
        description = "创建或完整更新一个前台模型版本组")
public record AdminModelFallbackGroupRequest(
        @Schema(description = "站点 ID；null 表示全局组", example = "1")
        Long siteId,

        @Schema(description = "稳定组 key，前台调用模型接口时使用", example = "gpt-image-2.0")
        @NotBlank(message = "groupKey must not be blank")
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}",
                message = "groupKey may contain letters, digits, dot, underscore and hyphen")
        String groupKey,

        @Schema(description = "前台展示的模型版本名称", example = "GPT-Image-2.0")
        @NotBlank(message = "displayName must not be blank")
        @Size(max = 128)
        String displayName,

        @Schema(description = "模型类型：CHAT / IMAGE / VIDEO / AUDIO", example = "IMAGE")
        @NotBlank(message = "modelType must not be blank")
        @Pattern(regexp = "CHAT|IMAGE|VIDEO|AUDIO",
                message = "modelType must be CHAT, IMAGE, VIDEO or AUDIO")
        String modelType,

        @Schema(description = "ACTIVE 在前台展示，DISABLED 隐藏", example = "ACTIVE")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status must be ACTIVE or DISABLED")
        String status,

        @Schema(description = "组之间的展示顺序", example = "10")
        @Min(value = 0, message = "sortOrder must not be negative")
        Integer sortOrder,

        @Schema(description = "时长读法，覆盖组内成员各自的声明："
                + "range（durations 是闭区间端点）/ set（durations 就是全部档位）。"
                + "留空表示按成员自动取交集",
                example = "range")
        @Pattern(regexp = "range|set", message = "durationMode must be range or set")
        String durationMode,

        @Schema(description = "可选时长（秒），含义由 durationMode 决定。"
                + "range 只给两个端点（如 [4,15] 表示 4–15 每个整数都能选），"
                + "set 逐个列出档位（如 [10,15]）。留空表示按成员自动取交集",
                example = "[4,15]")
        @Size(max = 64)
        List<@Min(1) @Max(60) Integer> durations
) {
}

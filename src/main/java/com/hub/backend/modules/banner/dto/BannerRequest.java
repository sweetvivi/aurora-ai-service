package com.hub.backend.modules.banner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Body of the admin create and full-replacement update calls.
 */
@Schema(name = "BannerRequest", description = "创建或更新顶部 Banner")
public record BannerRequest(
        @Schema(description = "后台内部名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "name must not be blank")
        @Size(max = 80, message = "name must be at most 80 characters")
        String name,

        @Schema(description = "前台主标题", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "title must not be blank")
        @Size(max = 80, message = "title must be at most 80 characters")
        String title,

        @Schema(description = "前台纯文本正文", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "content must not be blank")
        @Size(max = 240, message = "content must be at most 240 characters")
        String content,

        @Schema(description = "正文中需要高亮的子串")
        @Size(max = 80, message = "highlightText must be at most 80 characters")
        String highlightText,

        @Schema(description = "按钮文案")
        @Size(max = 20, message = "buttonText must be at most 20 characters")
        String buttonText,

        @Schema(description = "按钮跳转地址")
        @Size(max = 1000, message = "linkUrl must be at most 1000 characters")
        String linkUrl,

        @Schema(description = "背景图地址")
        @Size(max = 1000, message = "imageUrl must be at most 1000 characters")
        String imageUrl,

        @Schema(description = "状态：ACTIVE / DISABLED，缺省 ACTIVE", example = "ACTIVE")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "status must be ACTIVE or DISABLED")
        String status,

        @Schema(description = "站点 ID；null 表示全局")
        Long siteId,

        @Schema(description = "开始时间；null 表示立即生效")
        LocalDateTime startAt,

        @Schema(description = "结束时间，不包含边界；null 表示长期有效")
        LocalDateTime endAt,

        @Schema(description = "是否允许访客关闭；缺省 true")
        Boolean dismissible,

        @Schema(description = "排序号，越小越靠前；缺省 0")
        @Min(value = 0, message = "sortOrder must not be negative")
        @Max(value = 100000, message = "sortOrder must be at most 100000")
        Integer sortOrder
) {
}

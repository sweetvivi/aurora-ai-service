package com.hub.backend.modules.banner.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "Banner", description = "顶部活动 Banner")
public record BannerResponse(
        @Schema(description = "Banner ID") Long id,
        @Schema(description = "后台内部名称") String name,
        @Schema(description = "前台主标题") String title,
        @Schema(description = "前台纯文本正文") String content,
        @Schema(description = "正文高亮子串") String highlightText,
        @Schema(description = "按钮文案") String buttonText,
        @Schema(description = "按钮跳转地址") String linkUrl,
        @Schema(description = "背景图地址") String imageUrl,
        @Schema(description = "状态") String status,
        @Schema(description = "站点 ID；null 表示全局") Long siteId,
        @Schema(description = "开始时间") LocalDateTime startAt,
        @Schema(description = "结束时间，不包含边界") LocalDateTime endAt,
        @Schema(description = "是否允许访客关闭") boolean dismissible,
        @Schema(description = "排序号") int sortOrder,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt
) {
}

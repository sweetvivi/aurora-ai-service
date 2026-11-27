package com.hub.backend.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Offset pagination envelope for collection endpoints.
 *
 * @param items page content, already mapped to response DTOs
 * @param total total number of rows matching the query
 * @param page  zero based page index
 * @param size  requested page size
 */
@Schema(name = "PageResponse", description = "分页响应")
public record PageResponse<T>(
        @Schema(description = "当前页数据") List<T> items,
        @Schema(description = "满足条件的总条数", example = "42") long total,
        @Schema(description = "当前页码，从 0 开始", example = "0") int page,
        @Schema(description = "每页条数", example = "20") int size
) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(List.copyOf(items), total, page, size);
    }
}

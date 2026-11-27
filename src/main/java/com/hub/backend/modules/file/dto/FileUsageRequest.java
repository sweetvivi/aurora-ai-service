package com.hub.backend.modules.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 批量查询附件引用情况。
 *
 * <p>用 POST 而不是 `GET /files/usages?ids=1,2,3`：选择框一次可能勾几十张，
 * 拼进 query 会撞 URL 长度上限，也不便于以后加过滤条件。</p>
 */
@Schema(name = "FileUsageRequest", description = "要查询引用情况的附件 ID 列表")
public record FileUsageRequest(
        @Schema(description = "附件 ID，最多 50 个（多出的会被忽略）", example = "[123, 124]")
        List<Long> ids
) {
}

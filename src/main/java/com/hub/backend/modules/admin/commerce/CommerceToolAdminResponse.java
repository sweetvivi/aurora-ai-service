package com.hub.backend.modules.admin.commerce;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端看到的电商工具完整形态。
 *
 * <p>与前台 {@code CommerceToolResponse} 的差别：多了主键、站点、分类与状态、时间戳，
 * 并且把 JSON 列解析成对象（前台那份是给渲染用的，这里是给编辑表单用的）。
 * 解析失败时字段为 null，不会让整页 500。</p>
 */
@Schema(name = "CommerceToolAdmin", description = "后台电商工具")
public record CommerceToolAdminResponse(
        Long id,
        Long siteId,
        /** commerce 电商 / painting 绘画，决定它出现在哪个页面 */
        String scene,
        String toolKey,
        String category,
        String name,
        String description,
        String prompt,
        String form,
        String glyph,
        String iconUrl,
        String accent,
        Map<String, Object> uploadRoles,
        List<String> retouchModes,
        Map<String, Object> formSchema,
        int sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

package com.hub.backend.modules.commerce.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "CommerceTool", description = "电商工具")
public record CommerceToolResponse(
        String value,
        String label,
        String description,
        String prompt,
        String form,
        String glyph,
        @Schema(description = "自定义图标图片；非空时优先于 glyph")
        String iconUrl,
        String accent,
        Map<String, UploadMeta> uploadLabels,
        List<String> retouchModes,
        @Schema(description = "每个工具自己的表单定义；为空表示未配置，前端按 form 兜底")
        Map<String, Object> schema
) {

    @Schema(name = "CommerceUploadMeta", description = "上传槽位的文案")
    public record UploadMeta(String label, String description) {
    }
}

package com.hub.backend.modules.commerce.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CommerceCategory", description = "电商工具分类")
public record CommerceCategoryResponse(
        String label,
        List<CommerceToolResponse> items
) {
}

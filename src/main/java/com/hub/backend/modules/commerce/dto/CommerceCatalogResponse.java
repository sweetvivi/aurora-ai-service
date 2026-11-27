package com.hub.backend.modules.commerce.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CommerceCatalog", description = "电商工具目录")
public record CommerceCatalogResponse(
        List<CommerceCategoryResponse> categories
) {
}

package com.hub.backend.modules.smartedit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/v1/smart-editing/records/{id}/title}.
 */
@Schema(name = "SmartEditRecordTitleUpdateRequest", description = "修改智能修图项目名称")
public record RecordTitleUpdateRequest(
        @Schema(description = "新的项目名称", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "新的项目名称")
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title
) {
}

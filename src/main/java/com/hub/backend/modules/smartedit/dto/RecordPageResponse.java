package com.hub.backend.modules.smartedit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Page shape consumed by the smart-editing frontend.
 */
@Schema(name = "SmartEditRecordPage", description = "智能修图记录分页")
public record RecordPageResponse(
        List<RecordResponse> items,
        long total
) {
}

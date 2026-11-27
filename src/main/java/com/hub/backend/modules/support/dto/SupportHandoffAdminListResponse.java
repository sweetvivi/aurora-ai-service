package com.hub.backend.modules.support.dto;

import java.util.List;

public record SupportHandoffAdminListResponse(
        List<SupportHandoffAdminItem> list,
        long total,
        long pendingCount
) {
}

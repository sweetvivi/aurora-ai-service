package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "AdminModelFallbackGroup",
        description = "前台模型版本组、成员顺序和实际可用状态")
public record AdminModelFallbackGroupResponse(
        Long id,
        Long siteId,
        boolean global,
        String groupKey,
        String displayName,
        String modelType,
        String status,
        int sortOrder,
        boolean orderCustomized,
        String orderingMode,
        /** 运营为组设置的时长读法；null 表示按成员自动取交集 */
        String durationMode,
        /** 运营为组设置的时长；null 表示按成员自动取交集 */
        List<Integer> durations,
        int memberCount,
        int eligibleMemberCount,
        List<AdminModelFallbackMemberResponse> members,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

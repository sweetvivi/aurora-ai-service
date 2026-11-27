package com.hub.backend.modules.support.dto;

import java.time.LocalDateTime;

/** 前台转人工提交回执。 */
public record SupportHandoffResponse(
        long id,
        LocalDateTime createdAt
) {
}

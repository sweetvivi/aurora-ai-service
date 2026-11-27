package com.hub.backend.modules.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SiteVisitRequest(
        @NotBlank(message = "path is required")
        @Size(max = 512, message = "path must contain at most 512 characters")
        String path
) {
}

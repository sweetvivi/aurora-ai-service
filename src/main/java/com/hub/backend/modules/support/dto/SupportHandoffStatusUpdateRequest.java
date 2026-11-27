package com.hub.backend.modules.support.dto;

import jakarta.validation.constraints.NotBlank;

public record SupportHandoffStatusUpdateRequest(
        @NotBlank String status
) {
}

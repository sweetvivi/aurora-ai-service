package com.hub.backend.modules.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupportFaqUpsertRequest(
        @NotBlank @Size(max = 512) String question,
        @NotBlank String answer,
        @Size(max = 512) String keywords,
        Boolean enabled,
        Integer sortOrder
) {
}

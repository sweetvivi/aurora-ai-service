package com.hub.backend.modules.admin.operations;

import jakarta.validation.constraints.NotBlank;

public record AdminUserStatusRequest(@NotBlank String status) {
}

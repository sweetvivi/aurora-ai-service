package com.hub.backend.modules.admin.operations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminCreateUserRequest(
        @NotBlank @Size(min = 3, max = 128) String username,
        @Size(max = 128) String nickname,
        @Size(max = 1024) String remark) {
}

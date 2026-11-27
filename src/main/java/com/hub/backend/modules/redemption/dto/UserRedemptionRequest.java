package com.hub.backend.modules.redemption.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param code     the redemption code being spent
 * @param password the account password, only needed when this server session no longer holds it
 *                 (a restart or an expired vault entry). Omitted otherwise; the service ignores it
 *                 when the password is already in memory, so a normal redemption sends no secret.
 */
public record UserRedemptionRequest(
        @NotBlank(message = "code is required")
        @Size(min = 8, max = 128, message = "code length is invalid")
        String code,

        @Size(max = 128, message = "password length is invalid")
        String password
) {
}

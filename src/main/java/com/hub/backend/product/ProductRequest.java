package com.hub.backend.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,
        @NotNull(message = "price is required")
        @DecimalMin(value = "0.00", inclusive = true, message = "price must be greater than or equal to 0")
        @Digits(integer = 17, fraction = 2, message = "price must have at most 17 integer and 2 decimal digits")
        BigDecimal price
) {
}

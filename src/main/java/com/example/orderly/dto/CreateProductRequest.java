package com.example.orderly.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank(message = "name is required")
        String name,

        @NotNull(message = "price is required")
        @Positive(message = "price must be positive")
        BigDecimal price,

        @NotNull(message = "stock is required")
        @Min(value = 0, message = "stock cannot be negative")
        Integer stock
) {
}

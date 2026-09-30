package com.example.orderly.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 64, message = "idempotencyKey must be at most 64 characters")
        String idempotencyKey,

        @NotBlank(message = "customerEmail is required")
        @Email(message = "customerEmail must be a valid email address")
        String customerEmail,

        @NotEmpty(message = "at least one item is required")
        List<@Valid OrderItemRequest> items
) {
}

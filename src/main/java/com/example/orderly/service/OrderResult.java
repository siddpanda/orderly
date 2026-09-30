package com.example.orderly.service;

import com.example.orderly.dto.OrderResponse;

/**
 * Outcome of {@link OrderService#placeOrder}: the order plus whether this call
 * actually created it ({@code true}) or replayed an earlier idempotent request
 * ({@code false}). The controller maps this to 201 vs 200.
 */
public record OrderResult(OrderResponse order, boolean created) {
}

package com.example.orderly.dto;

import java.math.BigDecimal;

/**
 * Domain event published to the {@code orders} Kafka topic after an order is
 * paid. Downstream consumers (notifications, analytics, fulfilment) react
 * without the order path knowing they exist.
 */
public record OrderCreatedEvent(
        Long orderId,
        String customerEmail,
        BigDecimal totalAmount,
        int itemCount
) {
}

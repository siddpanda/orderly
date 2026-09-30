package com.example.orderly.service;

import com.example.orderly.dto.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Downstream consumer of the {@code orders} topic. It only knows about the
 * event — never about the order write path — so notifications can be scaled,
 * retried, or replaced without touching order placement.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @KafkaListener(topics = "${orderly.kafka.topic:orders}", groupId = "orderly-notifications")
    public void handleOrderCreated(OrderCreatedEvent event) {
        // Simulated email — in production this would call an email provider.
        log.info("Sending confirmation email to {} for order {}", event.customerEmail(), event.orderId());
    }
}

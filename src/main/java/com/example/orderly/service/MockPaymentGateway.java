package com.example.orderly.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Simulates a third-party payment gateway: a short artificial latency, then a
 * fake transaction id. Replace this {@link PaymentService} bean with a real
 * gateway client for production.
 */
@Component
public class MockPaymentGateway implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    @Override
    public PaymentResult charge(String customerEmail, BigDecimal amount) {
        try {
            Thread.sleep(300); // simulate network latency of a real gateway
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PaymentResult(null, false);
        }
        String transactionId = "txn_" + UUID.randomUUID();
        log.info("Mock payment of {} for {} approved as {}", amount, customerEmail, transactionId);
        return new PaymentResult(transactionId, true);
    }
}

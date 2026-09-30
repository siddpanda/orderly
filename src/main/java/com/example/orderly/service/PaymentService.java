package com.example.orderly.service;

import java.math.BigDecimal;

/**
 * Abstraction over a payment gateway. {@link OrderService} depends only on
 * this interface, so the mock below can be swapped for a real Stripe/Adyen
 * implementation without touching order logic.
 */
public interface PaymentService {

    /**
     * Charges the customer the given amount.
     *
     * @return the gateway's result, including the transaction id on success
     */
    PaymentResult charge(String customerEmail, BigDecimal amount);
}

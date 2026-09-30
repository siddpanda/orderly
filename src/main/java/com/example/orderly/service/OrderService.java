package com.example.orderly.service;

import com.example.orderly.dto.CreateOrderRequest;
import com.example.orderly.dto.OrderCreatedEvent;
import com.example.orderly.dto.OrderItemRequest;
import com.example.orderly.dto.OrderResponse;
import com.example.orderly.entity.CustomerOrder;
import com.example.orderly.entity.OrderItem;
import com.example.orderly.entity.OrderStatus;
import com.example.orderly.entity.Product;
import com.example.orderly.exception.InsufficientStockException;
import com.example.orderly.exception.PaymentFailedException;
import com.example.orderly.exception.ResourceNotFoundException;
import com.example.orderly.messaging.OrderEventPublisher;
import com.example.orderly.repository.OrderRepository;
import com.example.orderly.repository.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

/**
 * Core order workflow: reserve stock, charge the customer, emit an event.
 * Everything below runs in a single database transaction.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentService paymentService;
    private final OrderEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        PaymentService paymentService,
                        OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.paymentService = paymentService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Places an order.
     *
     * <ol>
     *   <li>If the idempotency key was seen before, the original order is returned
     *       and nothing else happens — safe to retry.</li>
     *   <li>Each product's stock is checked and decremented. Products are
     *       {@code @Version}-ed, so concurrent decrements of the same row fail
     *       with an optimistic-locking exception instead of overselling.</li>
     *   <li>The order and its items are persisted as PENDING.</li>
     *   <li>The (mock) payment gateway is charged; on success the order becomes PAID.</li>
     *   <li>An {@code OrderCreated} event is published to Kafka — but only after
     *       the database transaction commits, so we never emit an event for an
     *       order that rolled back.</li>
     * </ol>
     */
    @Transactional
    public OrderResult placeOrder(CreateOrderRequest request) {
        // (a) Idempotent replay: a retried request returns the original order.
        var existing = orderRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return new OrderResult(OrderResponse.from(existing.get()), false);
        }

        CustomerOrder order = new CustomerOrder();
        order.setCustomerEmail(request.customerEmail());
        order.setIdempotencyKey(request.idempotencyKey());
        order.setStatus(OrderStatus.PENDING);

        BigDecimal total = BigDecimal.ZERO;

        // (b) Reserve stock for every line item.
        for (OrderItemRequest itemRequest : request.items()) {
            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.productId()));

            if (product.getStock() < itemRequest.quantity()) {
                throw new InsufficientStockException(product.getId(), product.getStock(), itemRequest.quantity());
            }
            product.setStock(product.getStock() - itemRequest.quantity());

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.quantity()));
            total = total.add(lineTotal);

            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setQuantity(itemRequest.quantity());
            item.setUnitPrice(product.getPrice()); // snapshot the price at order time
            order.addItem(item);
        }

        // (c) Persist the order graph.
        order.setTotalAmount(total);
        final CustomerOrder saved;
        try {
            saved = orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another request carrying the same idempotency key:
            // the unique constraint rejected our insert, so return the winner's order.
            return orderRepository.findByIdempotencyKey(request.idempotencyKey())
                    .map(winner -> new OrderResult(OrderResponse.from(winner), false))
                    .orElseThrow(() -> e);
        }

        // (d) Charge via the payment gateway.
        PaymentResult payment = paymentService.charge(request.customerEmail(), total);
        if (!payment.success()) {
            throw new PaymentFailedException("Payment was declined for order " + saved.getId());
        }
        saved.setStatus(OrderStatus.PAID);

        // (e) Notify downstream systems via Kafka.
        OrderCreatedEvent event = new OrderCreatedEvent(
                saved.getId(),
                saved.getCustomerEmail(),
                saved.getTotalAmount(),
                saved.getItems().size());
        publishAfterCommit(event);

        return new OrderResult(OrderResponse.from(saved), true);
    }

    /**
     * Defers publishing until the surrounding transaction commits. Publishing
     * inside the transaction would risk emitting an event for an order that
     * later rolls back. (The production-grade version of this is the
     * transactional outbox pattern — see README.)
     */
    private void publishAfterCommit(OrderCreatedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(event);
                }
            });
        } else {
            eventPublisher.publish(event);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        CustomerOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByEmail(String email) {
        return orderRepository.findByCustomerEmailOrderByCreatedAtDesc(email).stream()
                .map(OrderResponse::from)
                .toList();
    }
}

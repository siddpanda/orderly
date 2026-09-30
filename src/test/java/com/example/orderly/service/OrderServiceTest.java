package com.example.orderly.service;

import com.example.orderly.dto.CreateOrderRequest;
import com.example.orderly.dto.ErrorResponse;
import com.example.orderly.dto.OrderCreatedEvent;
import com.example.orderly.dto.OrderItemRequest;
import com.example.orderly.entity.CustomerOrder;
import com.example.orderly.entity.OrderStatus;
import com.example.orderly.entity.Product;
import com.example.orderly.exception.GlobalExceptionHandler;
import com.example.orderly.exception.InsufficientStockException;
import com.example.orderly.messaging.OrderEventPublisher;
import com.example.orderly.repository.OrderRepository;
import com.example.orderly.repository.ProductRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private PaymentService paymentService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private HttpServletRequest httpServletRequest;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        OrderEventPublisher eventPublisher = new OrderEventPublisher(kafkaTemplate, "orders");
        orderService = new OrderService(orderRepository, productRepository, paymentService, eventPublisher);
    }

    private static Product product(Long id, String name, String price, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        return p;
    }

    private static CreateOrderRequest orderRequest(String idempotencyKey, Long productId, int quantity) {
        return new CreateOrderRequest(
                idempotencyKey,
                "customer@example.com",
                List.of(new OrderItemRequest(productId, quantity)));
    }

    @Test
    void replayingIdempotencyKeyReturnsExistingOrderWithoutSideEffects() {
        CustomerOrder existing = new CustomerOrder();
        existing.setId(42L);
        existing.setCustomerEmail("customer@example.com");
        existing.setIdempotencyKey("key-1");
        existing.setStatus(OrderStatus.PAID);
        existing.setTotalAmount(new BigDecimal("19.98"));

        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        OrderResult result = orderService.placeOrder(orderRequest("key-1", 1L, 2));

        assertThat(result.created()).isFalse();
        assertThat(result.order().id()).isEqualTo(42L);
        // No stock movement, no charge, no event on a replay.
        verifyNoInteractions(productRepository, paymentService, kafkaTemplate);
    }

    @Test
    void insufficientStockThrowsAndPublishesNothing() {
        when(orderRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product(1L, "Widget", "9.99", 1)));

        assertThrows(InsufficientStockException.class,
                () -> orderService.placeOrder(orderRequest("key-2", 1L, 2)));

        verify(orderRepository, never()).save(any(CustomerOrder.class));
        verify(paymentService, never()).charge(anyString(), any(BigDecimal.class));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void happyPathChargesPaymentAndPublishesOrderCreatedEvent() {
        Product widget = product(1L, "Widget", "9.99", 10);
        when(orderRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.empty());
        when(productRepository.findById(1L)).thenReturn(Optional.of(widget));
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(invocation -> {
            CustomerOrder order = invocation.getArgument(0);
            order.setId(7L);
            return order;
        });
        when(paymentService.charge(eq("customer@example.com"), eq(new BigDecimal("19.98"))))
                .thenReturn(new PaymentResult("txn_123", true));
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("orders"), eq("7"), any(OrderCreatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        OrderResult result = orderService.placeOrder(orderRequest("key-3", 1L, 2));

        assertThat(result.created()).isTrue();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.order().totalAmount()).isEqualByComparingTo("19.98");
        assertThat(widget.getStock()).isEqualTo(8);

        ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(kafkaTemplate).send(eq("orders"), eq("7"), eventCaptor.capture());
        OrderCreatedEvent event = eventCaptor.getValue();
        assertThat(event.orderId()).isEqualTo(7L);
        assertThat(event.customerEmail()).isEqualTo("customer@example.com");
        assertThat(event.totalAmount()).isEqualByComparingTo("19.98");
        assertThat(event.itemCount()).isEqualTo(1);
    }

    @Test
    void optimisticLockConflictPropagatesFor409Mapping() {
        when(orderRepository.findByIdempotencyKey("key-4")).thenReturn(Optional.empty());
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product(1L, "Widget", "9.99", 10)));
        when(orderRepository.save(any(CustomerOrder.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L));

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> orderService.placeOrder(orderRequest("key-4", 1L, 2)));

        // The failed order must not charge the customer or emit an event.
        verify(paymentService, never()).charge(anyString(), any(BigDecimal.class));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void adviceMapsOptimisticLockingTo409() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        when(httpServletRequest.getRequestURI()).thenReturn("/api/orders");

        ResponseEntity<ErrorResponse> response = advice.handleOptimisticLocking(
                new ObjectOptimisticLockingFailureException(Product.class, 1L),
                httpServletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("Conflict");
        assertThat(response.getBody().message()).contains("retry");
        assertThat(response.getBody().path()).isEqualTo("/api/orders");
    }
}

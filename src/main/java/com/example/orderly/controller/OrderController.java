package com.example.orderly.controller;

import com.example.orderly.dto.CreateOrderRequest;
import com.example.orderly.dto.OrderResponse;
import com.example.orderly.service.OrderResult;
import com.example.orderly.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Places an order. Returns 201 when the order is created, 200 when the
     * idempotency key was already seen (the original order is returned,
     * nothing is duplicated or double-charged).
     */
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResult result = orderService.placeOrder(request);
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.order());
        }
        return ResponseEntity.ok(result.order());
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    @GetMapping
    public List<OrderResponse> getOrders(@RequestParam String email) {
        return orderService.getOrdersByEmail(email);
    }
}

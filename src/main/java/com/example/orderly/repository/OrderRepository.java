package com.example.orderly.repository;

import com.example.orderly.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByIdempotencyKey(String idempotencyKey);

    List<CustomerOrder> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);
}

package com.example.orderly.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A sellable product. Stock is decremented when an order is placed.
 *
 * <p>The {@code @Version} field enables <b>optimistic locking</b>: every UPDATE
 * Hibernate issues for this row includes {@code WHERE id = ? AND version = ?}
 * and bumps the version column. If two transactions read the same product row
 * and both try to decrement stock, the second UPDATE matches zero rows and JPA
 * throws an {@link jakarta.persistence.OptimisticLockException}. That turns a
 * silent oversell (a lost update) into an explicit 409 the client can retry —
 * no database-level row lock is ever held while the request is processed.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Version
    private Long version;
}

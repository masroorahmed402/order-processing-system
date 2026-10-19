package com.example.orders.order.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for orders; implemented by the JPA adapter in the persistence package. */
public interface OrderRepository {

    Optional<Order> findById(UUID id);

    /** Inserts a new order or updates an existing one (optimistic locking applies). */
    Order save(Order order);

    /** Inserts and flushes immediately so a duplicate primary key fails inside the caller's transaction. */
    Order insert(Order order);
}

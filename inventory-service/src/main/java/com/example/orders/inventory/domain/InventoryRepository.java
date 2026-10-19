package com.example.orders.inventory.domain;

import com.example.orders.common.event.LineItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Stock and reservations. Implemented on DynamoDB with transactional, conditional writes. */
public interface InventoryRepository {

    /** Atomically decrements stock for every item and records the reservation, or does neither. */
    ReserveResult reserve(UUID orderId, String customerId, BigDecimal amount, List<LineItem> items, Instant now);

    /** Gives back exactly what {@link #reserve} took, at most once per order. */
    ReleaseResult release(UUID orderId, Instant now);

    Optional<Reservation> findReservation(UUID orderId);

    OptionalInt stockOf(String sku);
}

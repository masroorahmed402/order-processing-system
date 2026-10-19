package com.example.orders.inventory.domain;

import com.example.orders.common.event.LineItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Inventory's record of what it did for an order. It doubles as the deduplication marker: it is
 * written in the same DynamoDB transaction as the stock change, so "marker exists" and "stock was
 * changed" are always true together.
 */
public record Reservation(UUID orderId, ReservationStatus status, String customerId, BigDecimal amount,
                          List<LineItem> items, String reason, Instant updatedAt) {

    public Reservation {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static Reservation reserved(UUID orderId, String customerId, BigDecimal amount, List<LineItem> items, Instant now) {
        return new Reservation(orderId, ReservationStatus.RESERVED, customerId, amount, items, null, now);
    }

    public Reservation rejected(String reason) {
        return new Reservation(orderId, ReservationStatus.REJECTED, customerId, amount, items, reason, updatedAt);
    }

    /** Written when a release arrives for an order inventory never reserved, so a late reservation is refused. */
    public static Reservation releaseTombstone(UUID orderId, Instant now) {
        return new Reservation(orderId, ReservationStatus.RELEASED, null, null, List.of(),
                "released before any reservation", now);
    }

    /** Quantities per SKU (an order may list the same SKU twice; DynamoDB transactions need one write per item). */
    public Map<String, Integer> quantitiesBySku() {
        return quantitiesBySku(items);
    }

    public static Map<String, Integer> quantitiesBySku(List<LineItem> items) {
        Map<String, Integer> bySku = new TreeMap<>();
        items.forEach(i -> bySku.merge(i.sku(), i.quantity(), Integer::sum));
        return bySku;
    }
}

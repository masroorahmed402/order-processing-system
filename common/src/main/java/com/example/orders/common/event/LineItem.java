package com.example.orders.common.event;

import java.math.BigDecimal;
import java.util.Objects;

/** One SKU and quantity within an order. */
public record LineItem(String sku, int quantity, BigDecimal unitPrice) {

    public LineItem {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + unitPrice);
        }
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}

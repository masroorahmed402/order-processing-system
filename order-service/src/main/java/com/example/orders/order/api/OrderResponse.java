package com.example.orders.order.api;

import com.example.orders.order.domain.Order;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        UUID orderId,
        String customerId,
        String status,
        String inventoryStatus,
        String paymentStatus,
        BigDecimal totalAmount,
        List<Item> items,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(String sku, int quantity, BigDecimal unitPrice) {
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.customerId(), order.status().name(),
                order.inventoryStatus().name(), order.paymentStatus().name(), order.totalAmount(),
                order.items().stream().map(i -> new Item(i.sku(), i.quantity(), i.unitPrice())).toList(),
                order.failureReason(), order.createdAt(), order.updatedAt());
    }
}

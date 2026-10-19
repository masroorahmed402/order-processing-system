package com.example.orders.order.application;

import com.example.orders.order.domain.Order;

/** {@code created} is false when the request was a replay of an earlier one with the same key. */
public record PlacementResult(Order order, boolean created) {
}

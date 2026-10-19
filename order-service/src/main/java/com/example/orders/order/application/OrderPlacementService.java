package com.example.orders.order.application;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Places orders. */
@Service
public class OrderPlacementService {

    private final OrderCreator orderCreator;

    public OrderPlacementService(OrderCreator orderCreator) {
        this.orderCreator = orderCreator;
    }

    public PlacementResult place(String idempotencyKey, PlaceOrderCommand command) {
        return orderCreator.createIfAbsent(UUID.randomUUID(), idempotencyKey, command);
    }
}

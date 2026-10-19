package com.example.orders.order.application;

import com.example.orders.common.event.LineItem;

import java.util.List;

public record PlaceOrderCommand(String customerId, List<LineItem> items) {

    public PlaceOrderCommand {
        items = List.copyOf(items);
    }
}

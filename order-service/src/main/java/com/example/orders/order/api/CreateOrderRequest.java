package com.example.orders.order.api;

import com.example.orders.common.event.LineItem;
import com.example.orders.order.application.PlaceOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Size(max = 100) String customerId,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull Item> items) {

    public record Item(
            @NotBlank @Size(max = 64) String sku,
            @Positive int quantity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal unitPrice) {
    }

    public PlaceOrderCommand toCommand() {
        return new PlaceOrderCommand(customerId,
                items.stream().map(i -> new LineItem(i.sku(), i.quantity(), i.unitPrice())).toList());
    }
}

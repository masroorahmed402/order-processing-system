package com.example.orders.order.domain;

import com.example.orders.common.event.LineItem;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

@Embeddable
public class OrderItem {

    @Column(name = "sku", nullable = false)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    protected OrderItem() {
        // for JPA
    }

    public OrderItem(String sku, int quantity, BigDecimal unitPrice) {
        LineItem validated = new LineItem(sku, quantity, unitPrice);
        this.sku = validated.sku();
        this.quantity = validated.quantity();
        this.unitPrice = validated.unitPrice();
    }

    public static OrderItem from(LineItem item) {
        return new OrderItem(item.sku(), item.quantity(), item.unitPrice());
    }

    public LineItem toLineItem() {
        return new LineItem(sku, quantity, unitPrice);
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public String sku() {
        return sku;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }
}

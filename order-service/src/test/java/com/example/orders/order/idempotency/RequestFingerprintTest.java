package com.example.orders.order.idempotency;

import com.example.orders.common.event.LineItem;
import com.example.orders.order.application.PlaceOrderCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static PlaceOrderCommand command(String customer, String sku, int qty, String price) {
        return new PlaceOrderCommand(customer, List.of(new LineItem(sku, qty, new BigDecimal(price))));
    }

    @Test
    void sameRequestSameFingerprint() {
        assertThat(RequestFingerprint.of(command("c1", "SKU-001", 2, "10.00")))
                .isEqualTo(RequestFingerprint.of(command("c1", "SKU-001", 2, "10.00")))
                .hasSize(64);
    }

    @Test
    void priceScaleDoesNotMatter() {
        assertThat(RequestFingerprint.of(command("c1", "SKU-001", 2, "10.0")))
                .isEqualTo(RequestFingerprint.of(command("c1", "SKU-001", 2, "10.00")));
    }

    @Test
    void anyDifferenceChangesTheFingerprint() {
        String base = RequestFingerprint.of(command("c1", "SKU-001", 2, "10.00"));
        assertThat(RequestFingerprint.of(command("c2", "SKU-001", 2, "10.00"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(command("c1", "SKU-002", 2, "10.00"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(command("c1", "SKU-001", 3, "10.00"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(command("c1", "SKU-001", 2, "10.01"))).isNotEqualTo(base);
    }
}

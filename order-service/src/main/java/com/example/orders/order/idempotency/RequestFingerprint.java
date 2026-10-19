package com.example.orders.order.idempotency;

import com.example.orders.common.event.LineItem;
import com.example.orders.order.application.PlaceOrderCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over a canonical form of the request, used to detect an idempotency key being reused
 * for a different request. Prices are normalized so "10.0" and "10.00" are the same request.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(PlaceOrderCommand command) {
        StringBuilder canonical = new StringBuilder("customer=").append(command.customerId());
        for (LineItem item : command.items()) {
            canonical.append("|sku=").append(item.sku())
                    .append(";qty=").append(item.quantity())
                    .append(";price=").append(item.unitPrice().stripTrailingZeros().toPlainString());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.example.orders.order.idempotency;

/** The original request for this key is still being processed (HTTP 409; retry later). */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String key) {
        super("A request with Idempotency-Key '" + key + "' is still in progress");
    }
}

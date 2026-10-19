package com.example.orders.common.messaging;

/**
 * A failure that will happen again on every retry (malformed payload, impossible state).
 * Messages failing with this are moved straight to the dead-letter queue instead of being retried.
 * Every other exception is treated as transient and retried with exponential backoff.
 */
public class PermanentMessageException extends RuntimeException {

    public PermanentMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermanentMessageException(String message) {
        super(message);
    }
}

package com.example.orders.common.event;

import com.example.orders.common.messaging.PermanentMessageException;

/** The message body cannot be turned into a valid {@link OrderEvent}. Retrying will not help. */
public class MalformedEventException extends PermanentMessageException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

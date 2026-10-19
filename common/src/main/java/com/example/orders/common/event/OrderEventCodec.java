package com.example.orders.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON (de)serialization of {@link OrderEvent}. Unknown fields are ignored so producers can add
 * fields without breaking older consumers; missing required fields are a permanent failure.
 */
public class OrderEventCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public String encode(OrderEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + event.eventId(), e);
        }
    }

    public OrderEvent decode(String body) {
        if (body == null || body.isBlank()) {
            throw new MalformedEventException("Empty message body", null);
        }
        try {
            OrderEvent event = mapper.readValue(body, OrderEvent.class);
            if (event == null) {
                throw new MalformedEventException("Message body is JSON null", null);
            }
            return event.validated();
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            // Jackson wraps constructor failures (NPE/IAE from the record) in ValueInstantiationException,
            // a JsonProcessingException, so all invalid input ends up here.
            throw new MalformedEventException("Malformed order event: " + e.getMessage(), e);
        }
    }
}

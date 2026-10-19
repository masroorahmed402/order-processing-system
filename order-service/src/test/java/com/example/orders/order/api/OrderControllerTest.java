package com.example.orders.order.api;

import com.example.orders.order.application.OrderPlacementService;
import com.example.orders.order.application.OrderQueryService;
import com.example.orders.order.application.OrderRecoveryService;
import com.example.orders.order.application.PlacementResult;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderItem;
import com.example.orders.order.domain.OrderNotFoundException;
import com.example.orders.order.idempotency.IdempotencyKeyReuseException;
import com.example.orders.order.idempotency.RequestInProgressException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {OrderController.class, AdminController.class})
class OrderControllerTest {

    private static final String BODY = """
            {"customerId":"customer-1","items":[{"sku":"SKU-001","quantity":2,"unitPrice":19.99}]}
            """;

    @Autowired
    private MockMvc mvc;
    @MockBean
    private OrderPlacementService placement;
    @MockBean
    private OrderQueryService queries;
    @MockBean
    private OrderRecoveryService recovery;

    private final Order order = Order.place(UUID.randomUUID(), "key-1", "customer-1",
            List.of(new OrderItem("SKU-001", 2, new BigDecimal("19.99"))), Instant.parse("2026-10-19T10:00:00Z"));

    @Test
    void newOrderReturns201WithLocation() throws Exception {
        when(placement.place(eq("key-1"), any())).thenReturn(new PlacementResult(order, true));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/" + order.id()))
                .andExpect(jsonPath("$.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(39.98))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-001"));
    }

    @Test
    void replayReturns200() throws Exception {
        when(placement.place(eq("key-1"), any())).thenReturn(new PlacementResult(order, false));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.id().toString()));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(placement);
    }

    @Test
    void blankIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/orders").header("Idempotency-Key", " ").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(placement);
    }

    @Test
    void invalidBodyIs400() throws Exception {
        mvc.perform(post("/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c\",\"items\":[{\"sku\":\"SKU-001\",\"quantity\":0,\"unitPrice\":1}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"c\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(placement);
    }

    @Test
    void keyReuseWithDifferentBodyIs422() throws Exception {
        when(placement.place(eq("key-1"), any())).thenThrow(new IdempotencyKeyReuseException("key-1"));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("different request body")));
    }

    @Test
    void inFlightDuplicateIs409() throws Exception {
        when(placement.place(eq("key-1"), any())).thenThrow(new RequestInProgressException("key-1"));

        mvc.perform(post("/orders").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void getReturnsTheOrderOr404() throws Exception {
        when(queries.get(order.id())).thenReturn(order);
        UUID missing = UUID.randomUUID();
        when(queries.get(missing)).thenThrow(new OrderNotFoundException(missing));

        mvc.perform(get("/orders/{id}", order.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.inventoryStatus").value("PENDING"));
        mvc.perform(get("/orders/{id}", missing)).andExpect(status().isNotFound());
        mvc.perform(get("/orders/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void resumeReturns202ForStuckOrdersAnd409ForTerminalOnes() throws Exception {
        UUID stuck = UUID.randomUUID();
        UUID done = UUID.randomUUID();
        when(recovery.resume(stuck)).thenReturn(true);
        when(recovery.resume(done)).thenReturn(false);

        mvc.perform(post("/admin/orders/{id}/resume", stuck)).andExpect(status().isAccepted());
        mvc.perform(post("/admin/orders/{id}/resume", done)).andExpect(status().isConflict());
    }
}

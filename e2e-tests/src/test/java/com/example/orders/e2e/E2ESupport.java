package com.example.orders.e2e;

import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.payment.gateway.SimulatedPaymentGateway;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/** Helpers shared by the end-to-end tests: HTTP clients for each service and saga assertions. */
abstract class E2ESupport {

    protected static final SagaEnvironment env = SagaEnvironment.get();
    protected static final RestClient orders = client(env.baseUrl(env.order));
    protected static final RestClient inventory = client(env.baseUrl(env.inventory));
    protected static final RestClient payments = client(env.baseUrl(env.payment));

    private static RestClient client(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Tests assert on status codes themselves.
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    protected static ResponseEntity<Map<String, Object>> placeOrder(String idempotencyKey, String sku, int qty, String unitPrice) {
        String body = """
                {"customerId":"e2e-customer","items":[{"sku":"%s","quantity":%d,"unitPrice":%s}]}
                """.formatted(sku, qty, unitPrice);
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) orders.post().uri("/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }

    protected static UUID placeOrderId(String sku, int qty, String unitPrice) {
        ResponseEntity<Map<String, Object>> response = placeOrder("e2e-" + UUID.randomUUID(), sku, qty, unitPrice);
        if (response.getStatusCode().value() != 201) {
            throw new AssertionError("Expected 201 but got " + response.getStatusCode() + ": " + response.getBody());
        }
        return UUID.fromString((String) response.getBody().get("orderId"));
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> order(UUID orderId) {
        return orders.get().uri("/orders/{id}", orderId).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> payment(UUID orderId) {
        ResponseEntity<Map> response = payments.get().uri("/payments/{id}", orderId).retrieve().toEntity(Map.class);
        return response.getStatusCode().value() == 404 ? null : response.getBody();
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> reservation(UUID orderId) {
        ResponseEntity<Map> response = inventory.get().uri("/inventory/reservations/{id}", orderId).retrieve().toEntity(Map.class);
        return response.getStatusCode().value() == 404 ? null : response.getBody();
    }

    protected static void awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> status.equals(order(orderId).get("status")));
    }

    protected static String freshSku(int stock) {
        String sku = "E2E-" + UUID.randomUUID();
        AwsTestClients.putStock(sku, stock);
        return sku;
    }

    protected static SagaEnvironment.RecordingPaymentGateway gateway() {
        return env.payment.getBean(SagaEnvironment.RecordingPaymentGateway.class);
    }

    protected static SimulatedPaymentGateway simulatedGateway() {
        return (SimulatedPaymentGateway) gateway().delegate;
    }

    protected static JdbcTemplate ordersDb() {
        return env.order.getBean(JdbcTemplate.class);
    }

    protected static void awaitQueuesDrained(String... queues) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
            for (String q : queues) {
                if (AwsTestClients.approximateMessages(q) != 0) {
                    return false;
                }
            }
            return true;
        });
    }
}

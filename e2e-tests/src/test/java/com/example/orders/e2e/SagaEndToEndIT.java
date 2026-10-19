package com.example.orders.e2e;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.testsupport.AwsTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The full saga across order-service, inventory-service and payment-service. */
class SagaEndToEndIT extends E2ESupport {

    private final OrderEventCodec codec = new OrderEventCodec();

    @Test
    void happyPathEndsConfirmed() {
        String sku = freshSku(10);

        UUID orderId = placeOrderId(sku, 3, "19.99");

        awaitStatus(orderId, "CONFIRMED");
        Map<String, Object> order = order(orderId);
        assertThat(order.get("inventoryStatus")).isEqualTo("RESERVED");
        assertThat(order.get("paymentStatus")).isEqualTo("COMPLETED");
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(7);
        assertThat(payment(orderId).get("status")).isEqualTo("COMPLETED");
        assertThat(new BigDecimal(payment(orderId).get("amount").toString())).isEqualByComparingTo("59.97");
        assertThat(reservation(orderId).get("status")).isEqualTo("RESERVED");
        assertThat(simulatedGateway().chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void insufficientStockEndsFailedWithoutCharging() {
        String sku = freshSku(2);

        UUID orderId = placeOrderId(sku, 5, "10.00");

        awaitStatus(orderId, "FAILED");
        Map<String, Object> order = order(orderId);
        assertThat(order.get("inventoryStatus")).isEqualTo("FAILED");
        assertThat((String) order.get("failureReason")).contains("insufficient stock");
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(2);
        assertThat(payment(orderId)).isNull();
        assertThat(gateway().attempts(orderId)).isEmpty();
    }

    @Test
    void paymentDeclineEndsFailedAndRestoresStock() {
        String sku = freshSku(10);

        // 2 x 3000.00 = 6000.00, above the simulated gateway's 5000.00 limit.
        UUID orderId = placeOrderId(sku, 2, "3000.00");

        awaitStatus(orderId, "FAILED");
        Map<String, Object> order = order(orderId);
        assertThat(order.get("inventoryStatus")).isEqualTo("RESERVED");
        assertThat(order.get("paymentStatus")).isEqualTo("FAILED");
        assertThat((String) order.get("failureReason")).contains("exceeds limit");
        assertThat(payment(orderId).get("status")).isEqualTo("DECLINED");
        // Compensation: INVENTORY_RELEASE gives the 2 units back.
        await().atMost(Duration.ofSeconds(30)).until(() -> AwsTestClients.stockOf(sku) == 10);
        await().atMost(Duration.ofSeconds(10)).until(() -> "RELEASED".equals(reservation(orderId).get("status")));
        assertThat(simulatedGateway().chargeCount(orderId)).isZero();
    }

    @Test
    void duplicateApiCallsCreateOneOrder() throws Exception {
        String sku = freshSku(100);
        String key = "e2e-dup-" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> first = placeOrder(key, sku, 1, "5.00");
        ResponseEntity<Map<String, Object>> retry = placeOrder(key, sku, 1, "5.00");
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(retry.getBody().get("orderId")).isEqualTo(first.getBody().get("orderId"));

        String concurrentKey = "e2e-dup-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> placeOrder(concurrentKey, sku, 1, "5.00"));
        }
        Set<Object> ids = new java.util.HashSet<>();
        int created = 0;
        for (Future<ResponseEntity<Map<String, Object>>> f : pool.invokeAll(calls)) {
            ResponseEntity<Map<String, Object>> r = f.get();
            if (r.getStatusCode().value() == 201) {
                created++;
            }
            if (r.getStatusCode().is2xxSuccessful()) {
                ids.add(r.getBody().get("orderId"));
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(ids).hasSize(1);
        for (String k : List.of(key, concurrentKey)) {
            assertThat(ordersDb().queryForObject("select count(*) from orders where idempotency_key = ?", Integer.class, k))
                    .isEqualTo(1);
        }
        awaitStatus(UUID.fromString((String) first.getBody().get("orderId")), "CONFIRMED");
        awaitStatus(UUID.fromString(ids.iterator().next().toString()), "CONFIRMED");
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(98);
    }

    @Test
    void duplicateSqsDeliveriesCauseNoDoubleReservationOrDoubleCharge() {
        String sku = freshSku(20);
        UUID orderId = placeOrderId(sku, 4, "10.00");
        List<LineItem> items = List.of(new LineItem(sku, 4, new BigDecimal("10.00")));
        String orderCreated = codec.encode(OrderEvent.orderCreated(orderId, "e2e-customer", items, new BigDecimal("40.00"), Instant.now()));
        String inventoryReserved = codec.encode(OrderEvent.inventoryReserved(orderId, "e2e-customer", items, new BigDecimal("40.00"), Instant.now()));

        // Duplicates racing the original messages...
        for (int i = 0; i < 3; i++) {
            AwsTestClients.send(QueueNames.INVENTORY, orderCreated);
            AwsTestClients.send(QueueNames.PAYMENT, inventoryReserved);
        }
        awaitStatus(orderId, "CONFIRMED");
        // ...and late duplicates after the saga finished.
        for (int i = 0; i < 3; i++) {
            AwsTestClients.send(QueueNames.INVENTORY, orderCreated);
            AwsTestClients.send(QueueNames.PAYMENT, inventoryReserved);
        }
        awaitQueuesDrained(QueueNames.INVENTORY, QueueNames.PAYMENT, QueueNames.ORDER_EVENTS);

        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(16);
        assertThat(simulatedGateway().chargeCount(orderId)).isEqualTo(1);
        assertThat(payment(orderId).get("status")).isEqualTo("COMPLETED");
        assertThat(order(orderId).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void manyOrdersForTheSameSkuNeverOversell() throws Exception {
        String sku = freshSku(5);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Callable<UUID>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> placeOrderId(sku, 1, "1.00"));
        }
        List<UUID> orderIds = new ArrayList<>();
        for (Future<UUID> f : pool.invokeAll(calls)) {
            orderIds.add(f.get());
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(60)).until(() ->
                orderIds.stream().allMatch(id -> Set.of("CONFIRMED", "FAILED").contains((String) order(id).get("status"))));
        Map<Object, Long> byStatus = orderIds.stream().collect(Collectors.groupingBy(id -> order(id).get("status"), Collectors.counting()));
        assertThat(byStatus).containsEntry("CONFIRMED", 5L).containsEntry("FAILED", 7L);
        assertThat(AwsTestClients.stockOf(sku)).isZero();
    }
}

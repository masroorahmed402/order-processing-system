package com.example.orders.order.it;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.testsupport.AwsTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
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

class OrderApiIT extends OrderServiceIntegrationTest {

    private final OrderEventCodec codec = new OrderEventCodec();

    @Test
    void createReturns201AndPublishesOrderCreatedThroughTheOutbox() {
        ResponseEntity<Map> response = postOrder("api-" + UUID.randomUUID(), orderJson("c-1", "SKU-001", 2, "19.99"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String orderId = (String) response.getBody().get("orderId");
        assertThat(response.getHeaders().getLocation()).hasToString("/orders/" + orderId);
        assertThat(getOrder(orderId).get("status")).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<OrderEvent> events = AwsTestClients.peek(QueueNames.INVENTORY).stream()
                    .map(m -> codec.decode(m.body())).toList();
            assertThat(events).anySatisfy(e -> {
                assertThat(e.type()).isEqualTo(EventType.ORDER_CREATED);
                assertThat(e.orderId()).hasToString(orderId);
                assertThat(e.amount()).isEqualByComparingTo("39.98");
            });
        });
        Integer unpublished = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?::uuid and published_at is null", Integer.class, orderId);
        assertThat(unpublished).isZero();
    }

    @Test
    void replayWithSameKeyReturns200AndTheSameOrder() {
        String key = "api-" + UUID.randomUUID();
        String body = orderJson("c-2", "SKU-001", 1, "10.00");

        ResponseEntity<Map> first = postOrder(key, body);
        ResponseEntity<Map> replay = postOrder(key, body);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("orderId")).isEqualTo(first.getBody().get("orderId"));
        assertThat(ordersWithKey(key)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIs422() {
        String key = "api-" + UUID.randomUUID();
        postOrder(key, orderJson("c-3", "SKU-001", 1, "10.00"));

        ResponseEntity<Map> reused = postOrder(key, orderJson("c-3", "SKU-001", 5, "10.00"));

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(ordersWithKey(key)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        String key = "api-" + UUID.randomUUID();
        String body = orderJson("c-4", "SKU-001", 1, "10.00");
        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            calls.add(() -> postOrder(key, body));
        }

        List<ResponseEntity<Map>> responses = new ArrayList<>();
        for (Future<ResponseEntity<Map>> f : pool.invokeAll(calls)) {
            responses.add(f.get());
        }
        pool.shutdown();

        Map<Integer, Long> byStatus = responses.stream()
                .collect(Collectors.groupingBy(r -> r.getStatusCode().value(), Collectors.counting()));
        assertThat(byStatus.get(201)).isEqualTo(1L);
        assertThat(byStatus.keySet()).isSubsetOf(Set.of(200, 201, 409));
        Set<Object> ids = responses.stream().filter(r -> r.getStatusCode().is2xxSuccessful())
                .map(r -> r.getBody().get("orderId")).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(ordersWithKey(key)).isEqualTo(1);
    }

    @Test
    void requestValidation() {
        assertThat(postOrder(null, orderJson("c", "SKU-001", 1, "1.00")).getStatusCode().value()).isEqualTo(400);
        assertThat(postOrder("k-" + UUID.randomUUID(), orderJson("c", "SKU-001", 0, "1.00")).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/orders/" + UUID.randomUUID(), Map.class).getStatusCode().value()).isEqualTo(404);
    }

    private int ordersWithKey(String key) {
        return jdbc.queryForObject("select count(*) from orders where idempotency_key = ?", Integer.class, key);
    }
}

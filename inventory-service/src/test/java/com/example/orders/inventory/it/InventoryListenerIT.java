package com.example.orders.inventory.it;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.common.testsupport.TestInfrastructure;
import com.example.orders.inventory.InventoryServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = InventoryServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = InventoryServiceApplication.CONFIG_NAME)
@ActiveProfiles("it")
class InventoryListenerIT {

    private final OrderEventCodec codec = new OrderEventCodec();

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerAws(registry);
    }

    @BeforeEach
    void purge() {
        AwsTestClients.purge(QueueNames.PAYMENT);
        AwsTestClients.purge(QueueNames.ORDER_EVENTS);
    }

    @Test
    void orderCreatedIsReservedAndAnnouncedToPaymentAndOrderService() {
        String sku = "IT-" + UUID.randomUUID();
        AwsTestClients.putStock(sku, 10);
        UUID orderId = UUID.randomUUID();

        AwsTestClients.send(QueueNames.INVENTORY, codec.encode(created(orderId, sku, 3)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(AwsTestClients.peek(QueueNames.PAYMENT)).extracting(m -> codec.decode(m.body()))
                    .anySatisfy(e -> {
                        assertThat(e.type()).isEqualTo(EventType.INVENTORY_RESERVED);
                        assertThat(e.orderId()).isEqualTo(orderId);
                    });
            assertThat(AwsTestClients.peek(QueueNames.ORDER_EVENTS)).extracting(m -> codec.decode(m.body()).orderId())
                    .contains(orderId);
        });
        assertThat(http.getForObject("/inventory/" + sku, Map.class).get("stock")).isEqualTo(7);
        assertThat(http.getForObject("/inventory/reservations/" + orderId, Map.class).get("status")).isEqualTo("RESERVED");
    }

    @Test
    void duplicateDeliveriesReserveOnceAndCompensationRestoresStock() {
        String sku = "IT-" + UUID.randomUUID();
        AwsTestClients.putStock(sku, 10);
        UUID orderId = UUID.randomUUID();
        String body = codec.encode(created(orderId, sku, 4));

        for (int i = 0; i < 3; i++) {
            AwsTestClients.send(QueueNames.INVENTORY, body);
        }
        await().atMost(Duration.ofSeconds(15)).until(() -> AwsTestClients.approximateMessages(QueueNames.INVENTORY) == 0);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(6);

        String release = codec.encode(OrderEvent.inventoryRelease(orderId, "payment failed", Instant.now()));
        AwsTestClients.send(QueueNames.INVENTORY, release);
        AwsTestClients.send(QueueNames.INVENTORY, release);
        await().atMost(Duration.ofSeconds(15)).until(() -> AwsTestClients.approximateMessages(QueueNames.INVENTORY) == 0);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(10);
        assertThat(http.getForEntity("/inventory/missing-sku", Map.class).getStatusCode().value()).isEqualTo(404);
    }

    private static OrderEvent created(UUID orderId, String sku, int qty) {
        return OrderEvent.orderCreated(orderId, "c-1", List.of(new LineItem(sku, qty, new BigDecimal("2.00"))),
                new BigDecimal(qty * 2), Instant.now());
    }
}

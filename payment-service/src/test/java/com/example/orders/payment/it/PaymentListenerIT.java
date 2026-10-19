package com.example.orders.payment.it;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.common.testsupport.TestInfrastructure;
import com.example.orders.payment.PaymentServiceApplication;
import com.example.orders.payment.gateway.PaymentGateway;
import com.example.orders.payment.gateway.SimulatedPaymentGateway;
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

@SpringBootTest(classes = PaymentServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = PaymentServiceApplication.CONFIG_NAME)
@ActiveProfiles("it")
class PaymentListenerIT {

    private final OrderEventCodec codec = new OrderEventCodec();

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private PaymentGateway gateway;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerAws(registry);
    }

    @BeforeEach
    void purge() {
        AwsTestClients.purge(QueueNames.ORDER_EVENTS);
    }

    @Test
    void concurrentDuplicateDeliveriesChargeOnceAndAllReportTheSameTransaction() {
        UUID orderId = UUID.randomUUID();
        String body = codec.encode(OrderEvent.inventoryReserved(orderId, "c",
                List.of(new LineItem("SKU-001", 1, new BigDecimal("25.00"))), new BigDecimal("25.00"), Instant.now()));

        for (int i = 0; i < 5; i++) {
            AwsTestClients.send(QueueNames.PAYMENT, body);
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> AwsTestClients.approximateMessages(QueueNames.PAYMENT) == 0);
        assertThat(((SimulatedPaymentGateway) gateway).chargeCount(orderId)).isEqualTo(1);
        Map<?, ?> payment = http.getForObject("/payments/" + orderId, Map.class);
        assertThat(payment.get("status")).isEqualTo("COMPLETED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(AwsTestClients.peek(QueueNames.ORDER_EVENTS)).extracting(m -> codec.decode(m.body()))
                        .allSatisfy(e -> {
                            assertThat(e.type()).isEqualTo(EventType.PAYMENT_COMPLETED);
                            assertThat(e.transactionId()).isEqualTo(payment.get("transactionId"));
                        })
                        .isNotEmpty());
        assertThat(http.getForEntity("/payments/" + UUID.randomUUID(), Map.class).getStatusCode().value()).isEqualTo(404);
    }
}

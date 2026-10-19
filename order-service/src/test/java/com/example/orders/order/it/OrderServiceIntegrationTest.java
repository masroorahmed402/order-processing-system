package com.example.orders.order.it;

import com.example.orders.common.QueueNames;
import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.common.testsupport.TestInfrastructure;
import com.example.orders.order.OrderServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

/** Boots the real order-service against Testcontainers Postgres + LocalStack. */
@SpringBootTest(classes = OrderServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {OrderServiceApplication.CONFIG_NAME, "messaging.backoff.base-seconds=1"})
@ActiveProfiles("it")
abstract class OrderServiceIntegrationTest {

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerPostgres(registry);
        TestInfrastructure.registerAws(registry);
    }

    @BeforeEach
    void cleanQueues() {
        AwsTestClients.purge(QueueNames.INVENTORY);
        AwsTestClients.purge(QueueNames.deadLetterQueueOf(QueueNames.ORDER_EVENTS));
    }

    protected ResponseEntity<Map> postOrder(String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.postForEntity("/orders", new HttpEntity<>(body, headers), Map.class);
    }

    protected Map<?, ?> getOrder(Object orderId) {
        return http.getForObject("/orders/" + orderId, Map.class);
    }

    protected static String orderJson(String customer, String sku, int quantity, String price) {
        return """
                {"customerId":"%s","items":[{"sku":"%s","quantity":%d,"unitPrice":%s}]}
                """.formatted(customer, sku, quantity, price);
    }
}

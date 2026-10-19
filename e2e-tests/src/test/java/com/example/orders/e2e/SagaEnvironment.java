package com.example.orders.e2e;

import com.example.orders.common.testsupport.TestInfrastructure;
import com.example.orders.inventory.InventoryServiceApplication;
import com.example.orders.order.OrderServiceApplication;
import com.example.orders.payment.PaymentServiceApplication;
import com.example.orders.payment.gateway.ChargeRequest;
import com.example.orders.payment.gateway.ChargeResult;
import com.example.orders.payment.gateway.PaymentGateway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Starts the three real services (each its own Spring application context, as in production)
 * in this JVM, wired to one Testcontainers Postgres and one LocalStack. Started once and shared
 * by all end-to-end tests.
 */
public final class SagaEnvironment {

    private static SagaEnvironment instance;

    final ConfigurableApplicationContext order;
    final ConfigurableApplicationContext inventory;
    final ConfigurableApplicationContext payment;

    private SagaEnvironment() {
        LocalStackContainer ls = TestInfrastructure.localstack();
        PostgreSQLContainer<?> pg = TestInfrastructure.postgres();
        String[] common = {
                "--server.port=0",
                "--spring.profiles.active=e2e",
                "--spring.cloud.aws.endpoint=" + ls.getEndpoint(),
                "--spring.cloud.aws.region.static=" + ls.getRegion(),
                "--spring.cloud.aws.credentials.access-key=" + ls.getAccessKey(),
                "--spring.cloud.aws.credentials.secret-key=" + ls.getSecretKey(),
                "--spring.cloud.aws.cloudwatch.enabled=false",
                // 1s, 2s, 4s, 8s, 16s keeps the DLQ test reasonably fast.
                "--messaging.backoff.base-seconds=1",
        };
        // The services share this test classpath, so inventory/payment would see JPA; they have no database.
        String noDatabase = "--spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration";

        order = new SpringApplicationBuilder(OrderServiceApplication.class)
                .properties(OrderServiceApplication.CONFIG_NAME)
                .run(concat(common, "--spring.datasource.url=" + pg.getJdbcUrl(),
                        "--spring.datasource.username=" + pg.getUsername(),
                        "--spring.datasource.password=" + pg.getPassword()));
        inventory = new SpringApplicationBuilder(InventoryServiceApplication.class)
                .properties(InventoryServiceApplication.CONFIG_NAME)
                .run(concat(common, noDatabase));
        payment = new SpringApplicationBuilder(PaymentServiceApplication.class, RecordingGatewayConfiguration.class)
                .properties(PaymentServiceApplication.CONFIG_NAME)
                .run(concat(common, noDatabase));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            order.close();
            inventory.close();
            payment.close();
        }));
    }

    public static synchronized SagaEnvironment get() {
        if (instance == null) {
            instance = new SagaEnvironment();
        }
        return instance;
    }

    String baseUrl(ConfigurableApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    private static String[] concat(String[] base, String... extra) {
        return Stream.concat(Stream.of(base), Stream.of(extra)).toArray(String[]::new);
    }

    /** Wraps the payment service's gateway to record when each charge attempt happened. */
    @Configuration(proxyBeanMethods = false)
    static class RecordingGatewayConfiguration {

        @Bean
        static BeanPostProcessor recordingGatewayPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof PaymentGateway gateway && !(bean instanceof RecordingPaymentGateway)
                            ? new RecordingPaymentGateway(gateway) : bean;
                }
            };
        }
    }

    public static final class RecordingPaymentGateway implements PaymentGateway {

        final PaymentGateway delegate;
        private final Map<UUID, List<Instant>> attempts = new ConcurrentHashMap<>();

        RecordingPaymentGateway(PaymentGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChargeResult charge(ChargeRequest request) {
            attempts.computeIfAbsent(request.orderId(), id -> new CopyOnWriteArrayList<>()).add(Instant.now());
            return delegate.charge(request);
        }

        public List<Instant> attempts(UUID orderId) {
            return List.copyOf(attempts.getOrDefault(orderId, List.of()));
        }
    }
}

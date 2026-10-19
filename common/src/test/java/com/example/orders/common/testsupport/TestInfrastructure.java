package com.example.orders.common.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Singleton Testcontainers shared by every integration test in a JVM: one Postgres and one
 * LocalStack, bootstrapped with the same init script docker-compose uses
 * (infra/localstack/init-aws.sh), so tests run against exactly the local topology.
 * Containers are stopped by Testcontainers' Ryuk when the JVM exits.
 */
public final class TestInfrastructure {

    private static final String LOCALSTACK_IMAGE = System.getProperty("localstack.image", "localstack/localstack:4.0.3");
    private static final String POSTGRES_IMAGE = System.getProperty("postgres.image", "postgres:16.4-alpine");

    private static PostgreSQLContainer<?> postgres;
    private static LocalStackContainer localstack;

    private TestInfrastructure() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("orders")
                    .withUsername("orders")
                    .withPassword("orders")
                    .withCommand("postgres", "-c", "max_connections=200");
            postgres.start();
        }
        return postgres;
    }

    public static synchronized LocalStackContainer localstack() {
        if (localstack == null) {
            localstack = new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.DYNAMODB,
                            LocalStackContainer.Service.CLOUDWATCH)
                    .withEnv("SQS_ENDPOINT_STRATEGY", "path")
                    .withCopyFileToContainer(MountableFile.forHostPath(initScript(), 0755),
                            "/etc/localstack/init/ready.d/init-aws.sh")
                    .waitingFor(Wait.forHttp("/_localstack/init/ready")
                            .forPort(4566)
                            .forResponsePredicate(body -> body.contains("\"completed\": true"))
                            .withStartupTimeout(Duration.ofMinutes(3)));
            localstack.start();
        }
        return localstack;
    }

    public static String awsEndpoint() {
        return localstack().getEndpoint().toString();
    }

    /** Points Spring Cloud AWS at LocalStack. Metrics export stays off in tests. */
    public static void registerAws(DynamicPropertyRegistry registry) {
        LocalStackContainer ls = localstack();
        registry.add("spring.cloud.aws.endpoint", () -> ls.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", ls::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", ls::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", ls::getSecretKey);
        registry.add("spring.cloud.aws.cloudwatch.enabled", () -> "false");
    }

    public static void registerPostgres(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> pg = postgres();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    /** Locates infra/localstack/init-aws.sh from any module directory. */
    static Path initScript() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("infra/localstack/init-aws.sh");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("infra/localstack/init-aws.sh not found above " + Path.of("").toAbsolutePath());
    }
}

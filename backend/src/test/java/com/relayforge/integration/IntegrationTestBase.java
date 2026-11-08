package com.relayforge.integration;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for integration tests.
 *
 * <h2>Why real containers rather than an in-memory substitute</h2>
 *
 * Every mechanism worth testing here depends on behaviour only the real database
 * provides: {@code FOR UPDATE SKIP LOCKED}, partial indexes, {@code jsonb},
 * {@code RETURNING}, and the exact semantics of a conditional {@code UPDATE}
 * under concurrency. H2 in PostgreSQL-compatibility mode implements none of them
 * faithfully, so a test suite built on it would pass while the production query
 * was wrong — which is worse than having no test.
 *
 * <h2>Each subclass declares its own role</h2>
 *
 * {@code relayforge.role} is deliberately <em>not</em> set here. A
 * {@code @DynamicPropertySource} method on a superclass and one on a subclass
 * both run, and the order between them is unspecified — so a property set in
 * both places has an ambiguous winner. Each concrete test class sets the role
 * it needs:
 *
 * <ul>
 *   <li>{@code api} for classes that insert delivery rows and assert on their
 *       exact state. The {@code @WorkerRole} beans do not exist, so no
 *       background scheduler can claim a fixture row out from under the test.
 *   <li>{@code all} for {@link DeliveryPipelineIT}, which is testing the
 *       workers themselves.
 * </ul>
 *
 * <h2>Containers are static and shared</h2>
 *
 * Started once for the whole suite rather than per class. Starting PostgreSQL
 * and Kafka takes tens of seconds; doing that per test class would make the
 * suite slow enough that people stop running it. Each test cleans the tables it
 * touches instead.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("relayforge_test")
                    .withUsername("relayforge")
                    .withPassword("test")
                    // Reused across the whole suite; see the class comment.
                    .withReuse(false);

    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7.4-alpine"));

    // org.testcontainers.kafka.KafkaContainer, not the deprecated
    // org.testcontainers.containers one — only this class supports the official
    // apache/kafka image in KRaft mode. The older class assumes a Confluent
    // image and fails at construction.
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        // Started in parallel: three sequential container starts would add
        // roughly a minute to every suite run.
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Test-only secrets. Real ones come from the environment; these exist so
        // the context starts, and are obviously not production values.
        registry.add(
                "relayforge.jwt.secret",
                () -> "integration-test-signing-secret-that-is-at-least-sixty-four-bytes-long-ok");
        registry.add(
                "relayforge.crypto.encryption-key",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));

        // Short intervals so a test does not wait on production-tuned timers.
        registry.add("relayforge.outbox.poll-interval", () -> "PT0.2S");
        registry.add("relayforge.outbox.dispatch-interval", () -> "PT0.2S");
        registry.add("relayforge.outbox.reconcile-interval", () -> "PT0.5S");
        registry.add("relayforge.retry.initial-delay", () -> "PT1S");
        registry.add("relayforge.retry.max-delay", () -> "PT5S");

        registry.add("relayforge.egress.profile", () -> "dev");
        // WireMock binds on loopback, which the policy blocks by default.
        registry.add("relayforge.egress.dev-allowlist", () -> "localhost,127.0.0.1");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired protected JdbcTemplate jdbc;

    /**
     * Truncates every domain table.
     *
     * <p>One statement with {@code CASCADE} rather than per-table deletes in
     * dependency order: the order is a detail of the schema that would need
     * updating with every migration, and getting it wrong produces a confusing
     * foreign-key error rather than a clean failure.
     */
    protected void resetDatabase() {
        jdbc.execute(
                """
                TRUNCATE TABLE
                    delivery_attempts, deliveries, outbox_events, events,
                    replay_records, playground_runs, delivery_minute_stats,
                    webhook_endpoints, api_keys, refresh_tokens,
                    tenant_memberships, users, tenants
                RESTART IDENTITY CASCADE
                """);
    }
}

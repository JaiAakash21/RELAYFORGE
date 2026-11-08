package com.relayforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RelayForge — reliable multi-tenant webhook delivery.
 *
 * <p>One artefact, three runtime roles, selected with {@code relayforge.role}:
 *
 * <ul>
 *   <li>{@code api} — serves the REST API. Does not consume Kafka and does not
 *       run the outbox publisher or any scheduled reconciliation.
 *   <li>{@code worker} — consumes delivery requests, performs the outbound HTTP
 *       calls, publishes the outbox and reconciles expired leases. Exposes only
 *       actuator endpoints.
 *   <li>{@code all} — both of the above in a single process. This is the default
 *       and is what local development and the test suite use.
 * </ul>
 *
 * <p>Splitting the roles at configuration time rather than at build time keeps a
 * single deployable and a single codebase while still letting the API scale on
 * request volume and the workers scale on delivery volume — the two dimensions
 * that move independently in a webhook platform.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RelayForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayForgeApplication.class, args);
    }
}

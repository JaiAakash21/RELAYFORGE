package com.relayforge.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable in one validated place, bound from {@code relayforge.*}.
 *
 * <p>Validation annotations are not decoration: a mistyped backoff or a missing
 * encryption key fails the application at startup with a precise message, rather
 * than at 3am with a corrupted secret or a retry storm.
 */
@ConfigurationProperties(prefix = "relayforge")
@Validated
public record RelayForgeProperties(
        @NotNull Role role,
        @NotNull Jwt jwt,
        @NotNull Crypto crypto,
        @NotNull Delivery delivery,
        @NotNull Retry retry,
        @NotNull Outbox outbox,
        @NotNull Egress egress,
        @NotNull RateLimits rateLimits,
        @NotNull Ingestion ingestion,
        @NotNull Playground playground,
        @NotNull Cors cors,
        @NotNull Demo demo) {

    /** Which half of the system this process runs. */
    public enum Role {
        API,
        WORKER,
        ALL;

        public boolean servesApi() {
            return this == API || this == ALL;
        }

        public boolean runsWorkers() {
            return this == WORKER || this == ALL;
        }
    }

    public record Jwt(
            /** HS512 signing secret. Must carry at least 512 bits of entropy. */
            @NotBlank String secret,
            @NotNull Duration accessTtl,
            @NotNull Duration refreshTtl,
            @NotBlank String issuer) {}

    public record Crypto(
            /** Base64-encoded 32-byte key for AES-256-GCM sealing of endpoint secrets. */
            @NotBlank String encryptionKey,
            /**
             * How long a rotated-out endpoint secret stays acceptable. Gives a
             * receiver time to roll its configuration without dropping events.
             */
            @NotNull Duration secretRotationGrace) {}

    public record Delivery(
            /** Workers processing deliveries concurrently in this process. */
            @Min(1) int concurrency,
            /** How long a worker's claim on a delivery is valid. */
            @NotNull Duration leaseDuration,
            /** Connect timeout for an outbound webhook request. */
            @NotNull Duration connectTimeout,
            /** Response timeout for an outbound webhook request. */
            @NotNull Duration responseTimeout,
            /** Largest response body we are willing to read, in bytes. */
            @Min(1024) int maxResponseBytes,
            /** Largest request body we are willing to send, in bytes. */
            @Min(1024) int maxRequestBytes,
            /** Pooled connections in total, and per destination route. */
            @Min(1) int maxPooledConnections,
            @Min(1) int maxConnectionsPerRoute,
            /** User-Agent presented to receivers. */
            @NotBlank String userAgent) {}

    public record Retry(
            @Min(1) int maxAttempts,
            @NotNull Duration initialDelay,
            @NotNull Duration maxDelay,
            /** Fraction of the computed delay that is randomised. 0 = none, 1 = full. */
            double jitterFactor,
            /** Status codes treated as retryable in addition to all 5xx. */
            @NotNull Set<Integer> retryableStatusCodes,
            /** Upper bound honoured from a receiver's Retry-After header. */
            @NotNull Duration maxRetryAfter,
            /** Extra attempts granted when an operator replays a dead letter. */
            @Min(1) int replayExtraAttempts) {}

    public record Outbox(
            @Min(1) int batchSize,
            @NotNull Duration pollInterval,
            /** How long a publisher's claim lasts before another may take the row. */
            @NotNull Duration claimTtl,
            /** Publish failures tolerated before a row is parked as DEAD. */
            @Min(1) int maxPublishAttempts,
            /** Dispatcher batch size and poll cadence for due retries. */
            @Min(1) int dispatchBatchSize,
            @NotNull Duration dispatchInterval,
            /**
             * A delivery whose Kafka message was handed over longer ago than this
             * is assumed lost and re-enqueued. Must comfortably exceed the time a
             * healthy attempt takes, or healthy work is enqueued twice.
             */
            @NotNull Duration requeueGrace,
            @NotNull Duration reconcileInterval,
            /** Published outbox rows older than this are pruned. */
            @NotNull Duration retainPublishedFor) {}

    public record Egress(
            /** {@code dev} relaxes the private-IP block for allowlisted hosts; {@code prod} does not. */
            @NotNull Profile profile,
            /** Hostnames exempt from the private-address block in the dev profile. */
            @NotNull Set<String> devAllowlist,
            /** Maximum time allowed for DNS resolution of a destination. */
            @NotNull Duration dnsTimeout,
            /** Ports a webhook URL may use. */
            @NotNull Set<Integer> allowedPorts) {

        public enum Profile {
            DEV,
            PROD
        }
    }

    public record RateLimits(
            /** Events per second a tenant may ingest, and the burst it may bank. */
            @Min(1) int tenantEventsPerSecond,
            @Min(1) int tenantEventsBurst,
            /** Per-API-key ceiling, applied in addition to the tenant ceiling. */
            @Min(1) int apiKeyEventsPerSecond,
            @Min(1) int apiKeyEventsBurst,
            /** Outbound attempts per second per endpoint. */
            @Min(1) int endpointDeliveriesPerSecond,
            @Min(1) int endpointDeliveriesBurst,
            /** Authentication attempts per minute per client, to blunt credential stuffing. */
            @Min(1) int authAttemptsPerMinute,
            /**
             * What to do when Redis cannot be reached. {@code true} lets traffic
             * through (availability first); {@code false} rejects it.
             */
            boolean failOpen) {}

    public record Ingestion(
            /** Largest accepted event payload, in bytes. */
            @Min(128) int maxPayloadBytes,
            /** Deliveries one event may fan out to before the request is rejected. */
            @Min(1) int maxFanout,
            @NotNull Set<String> reservedEventTypePrefixes) {}

    public record Playground(
            /** Base URL of the controlled simulator. Must not be a public host. */
            @NotBlank String simulatorBaseUrl,
            /** Attempt budget used by playground endpoints, kept small so demos finish. */
            @Min(1) int maxAttempts,
            @NotNull Duration initialDelay,
            @NotNull Duration maxDelay) {}

    public record Cors(@NotNull List<String> allowedOrigins) {}

    public record Demo(boolean seedEnabled, String email, String password, String tenantName) {}
}

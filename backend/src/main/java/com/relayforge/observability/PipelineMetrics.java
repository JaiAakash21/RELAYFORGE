package com.relayforge.observability;

import com.relayforge.delivery.AttemptOutcome;
import com.relayforge.delivery.ErrorClass;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Application metrics for the event pipeline.
 *
 * <h2>Label cardinality is the whole design constraint</h2>
 *
 * Prometheus stores one time series per unique label combination, and those
 * series are held in memory. The tempting labels here are the dangerous ones:
 *
 * <ul>
 *   <li><b>No {@code tenant_id}.</b> Series count would grow linearly with
 *       customers — the one number a platform wants to grow. Per-tenant numbers
 *       are served from {@link DeliveryStatsDao}, where they are rows in
 *       PostgreSQL and cost nothing to add.
 *   <li><b>No {@code endpoint_id}, no URL, no hostname.</b> Unbounded and
 *       user-controlled: a tenant could multiply our memory use by registering
 *       endpoints.
 *   <li><b>No raw HTTP status.</b> Bucketed to {@code 2xx}/{@code 4xx}/{@code
 *       5xx} and a few individually interesting codes, because a receiver can
 *       return any integer it likes.
 *   <li><b>No exception messages.</b> {@link ErrorClass} is a closed enum for
 *       exactly this reason.
 * </ul>
 *
 * Every label used here comes from a fixed, code-defined set, so the series
 * count is bounded at build time rather than by how our users behave.
 */
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    private final Counter eventsAccepted;
    private final Counter eventsDeduplicated;
    private final Counter eventsRejected;
    private final Counter deliveriesCreated;
    private final Counter retriesScheduled;
    private final Counter deadLettered;
    private final Counter replays;
    private final Counter leasesRecovered;
    private final Counter outboxPublished;
    private final Counter outboxPublishFailures;
    private final Counter outboxAckFailures;
    private final Counter duplicateMessages;
    private final Counter endpointThrottled;
    private final Timer attemptTimer;

    /** Pre-registered per-label counters, so the hot path never builds a meter. */
    private final Map<String, Counter> attemptsByOutcome = new ConcurrentHashMap<>();
    private final Map<String, Counter> attemptsByStatusClass = new ConcurrentHashMap<>();
    private final Map<String, Counter> attemptsByErrorClass = new ConcurrentHashMap<>();

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.eventsAccepted =
                counter("relayforge.events.accepted", "Events accepted for delivery");
        this.eventsDeduplicated =
                counter(
                        "relayforge.events.deduplicated",
                        "Ingestion requests short-circuited by an idempotency key");
        this.eventsRejected =
                counter("relayforge.events.rejected", "Ingestion requests rejected as invalid");
        this.deliveriesCreated =
                counter("relayforge.deliveries.created", "Delivery rows created by fan-out");
        this.retriesScheduled =
                counter("relayforge.deliveries.retries.scheduled", "Retries scheduled");
        this.deadLettered =
                counter(
                        "relayforge.deliveries.dead_lettered",
                        "Deliveries that exhausted their attempt budget");
        this.replays = counter("relayforge.deliveries.replayed", "Manual replays requested");
        this.leasesRecovered =
                counter(
                        "relayforge.deliveries.leases.recovered",
                        "Deliveries recovered from an expired worker lease");
        this.outboxPublished =
                counter("relayforge.outbox.published", "Outbox records acknowledged by the broker");
        this.outboxPublishFailures =
                counter("relayforge.outbox.publish_failures", "Outbox publish attempts that failed");
        this.outboxAckFailures =
                counter(
                        "relayforge.outbox.ack_failures",
                        "Records published to Kafka that could not be marked published");
        this.duplicateMessages =
                counter(
                        "relayforge.deliveries.duplicate_messages",
                        "Kafka messages for a delivery that was not claimable — duplicates absorbed"
                                + " by the idempotent claim");
        this.endpointThrottled =
                counter(
                        "relayforge.deliveries.endpoint_throttled",
                        "Attempts deferred by a per-endpoint delivery rate limit");

        this.attemptTimer =
                Timer.builder("relayforge.delivery.attempt.duration")
                        .description("Wall-clock duration of one outbound webhook attempt")
                        // Explicit SLO boundaries produce a histogram Prometheus can
                        // compute real quantiles from, rather than a client-side
                        // estimate that cannot be aggregated across instances.
                        .serviceLevelObjectives(
                                Duration.ofMillis(50),
                                Duration.ofMillis(100),
                                Duration.ofMillis(250),
                                Duration.ofMillis(500),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(10))
                        .publishPercentileHistogram()
                        .register(registry);
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public void recordEventAccepted(int deliveryCount) {
        eventsAccepted.increment();
        if (deliveryCount > 0) {
            deliveriesCreated.increment(deliveryCount);
        }
    }

    public void recordEventDeduplicated() {
        eventsDeduplicated.increment();
    }

    public void recordEventRejected() {
        eventsRejected.increment();
    }

    /**
     * Records one completed attempt.
     *
     * @param status the HTTP status, or {@code null} for a transport failure
     */
    public void recordAttempt(
            AttemptOutcome outcome, Integer status, ErrorClass errorClass, int durationMs) {
        attemptsByOutcome
                .computeIfAbsent(
                        outcome.name(),
                        key ->
                                Counter.builder("relayforge.delivery.attempts")
                                        .description("Outbound webhook attempts by outcome")
                                        .tag("outcome", key.toLowerCase(java.util.Locale.ROOT))
                                        .register(registry))
                .increment();

        String statusClass = statusClass(status);
        attemptsByStatusClass
                .computeIfAbsent(
                        statusClass,
                        key ->
                                Counter.builder("relayforge.delivery.responses")
                                        .description(
                                                "Outbound webhook responses by status class")
                                        .tag("status_class", key)
                                        .register(registry))
                .increment();

        if (errorClass != null && errorClass != ErrorClass.NONE) {
            attemptsByErrorClass
                    .computeIfAbsent(
                            errorClass.name(),
                            key ->
                                    Counter.builder("relayforge.delivery.errors")
                                            .description("Delivery failures by error class")
                                            .tag(
                                                    "error_class",
                                                    key.toLowerCase(java.util.Locale.ROOT))
                                            .register(registry))
                    .increment();
        }

        attemptTimer.record(Duration.ofMillis(durationMs));
    }

    /**
     * Buckets an HTTP status into a bounded label.
     *
     * <p>429 and 503 are kept individually because they drive very different
     * operational responses from a generic 4xx or 5xx, and both are closed-set
     * values we choose — not arbitrary integers from a receiver.
     */
    private static String statusClass(Integer status) {
        if (status == null) {
            return "transport_error";
        }
        if (status == 429) {
            return "429";
        }
        if (status == 503) {
            return "503";
        }
        return (status / 100) + "xx";
    }

    public void recordRetryScheduled() {
        retriesScheduled.increment();
    }

    public void recordDeadLettered() {
        deadLettered.increment();
    }

    public void recordReplay(int count) {
        replays.increment(count);
    }

    public void recordLeasesRecovered(int count) {
        leasesRecovered.increment(count);
    }

    public void recordOutboxPublished() {
        outboxPublished.increment();
    }

    public void recordOutboxPublishFailure() {
        outboxPublishFailures.increment();
    }

    public void recordOutboxAckFailure(int count) {
        outboxAckFailures.increment(count);
    }

    public void recordDuplicateMessage() {
        duplicateMessages.increment();
    }

    public void recordEndpointThrottled() {
        endpointThrottled.increment();
    }
}

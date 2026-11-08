package com.relayforge.observability.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response shapes for the tenant-facing metrics API. */
public final class MetricsDtos {

    private MetricsDtos() {}

    /**
     * The overview dashboard's figures.
     *
     * <p>Every field is computed from stored rows. Nothing here is a constant,
     * and a tenant with no traffic sees zeros rather than invented numbers.
     *
     * @param approximateLatency true because the percentiles come from fixed
     *     histogram buckets, not exact order statistics — surfaced so the UI can
     *     label them honestly rather than implying a precision it does not have
     */
    public record Summary(
            long totalEvents,
            long eventsInWindow,
            long totalDeliveries,
            long successfulDeliveries,
            long failedDeliveries,
            long pendingDeliveries,
            long inProgressDeliveries,
            long deadLetterCount,
            double successRate,
            double avgLatencyMs,
            double p50LatencyMs,
            double p95LatencyMs,
            double p99LatencyMs,
            long attemptsInWindow,
            long retriesInWindow,
            long replaysInWindow,
            int activeEndpoints,
            QueueHealth queue,
            String window,
            boolean approximateLatency,
            Instant generatedAt) {}

    /**
     * Pipeline backlog.
     *
     * @param oldestDueAgeSeconds how long the oldest ready delivery has been
     *     waiting. This, not Kafka consumer lag, is the number that corresponds
     *     to what a user experiences.
     */
    public record QueueHealth(
            long dueBacklog,
            long inProgress,
            double oldestDueAgeSeconds,
            long unpublishedOutboxRecords,
            double oldestUnpublishedOutboxAgeSeconds) {}

    public record TimeseriesPoint(
            Instant timestamp,
            long attempts,
            long successes,
            long retryableFailures,
            long terminalFailures,
            double avgDurationMs,
            int maxDurationMs) {}

    public record Timeseries(
            String window, int bucketMinutes, List<TimeseriesPoint> points) {}

    public record EndpointPerformance(
            UUID endpointId,
            String name,
            String url,
            long succeeded,
            long failed,
            long pending,
            double successRate,
            String health) {}
}

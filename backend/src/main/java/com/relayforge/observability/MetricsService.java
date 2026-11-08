package com.relayforge.observability;

import com.relayforge.deadletter.ReplayRecordRepository;
import com.relayforge.delivery.DeliveryAttemptRepository;
import com.relayforge.delivery.DeliveryClaimDao;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.DeliveryStatus;
import com.relayforge.endpoints.EndpointService;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.endpoints.WebhookEndpointRepository;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.events.EventRepository;
import com.relayforge.observability.dto.MetricsDtos;
import com.relayforge.outbox.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the tenant-facing metrics the dashboard displays.
 *
 * <h2>Two metrics systems, on purpose</h2>
 *
 * This service answers <em>per-tenant</em> questions and reads from PostgreSQL.
 * {@link PipelineMetrics} answers <em>operational</em> questions and exports to
 * Prometheus. They are not duplicates, and merging them would break one of them:
 *
 * <ul>
 *   <li>Per-tenant figures in Prometheus would need a {@code tenant_id} label,
 *       which makes the series count grow with the customer base.
 *   <li>Operator figures in PostgreSQL would need the dashboard to aggregate
 *       across tenants on every page load.
 * </ul>
 *
 * So tenant numbers live in rows, and operator numbers live in time series.
 */
@Service
public class MetricsService {

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final DeliveryClaimDao claimDao;
    private final DeliveryStatsDao stats;
    private final OutboxEventRepository outbox;
    private final ReplayRecordRepository replays;
    private final WebhookEndpointRepository endpoints;
    private final EndpointService endpointService;

    public MetricsService(
            EventRepository events,
            DeliveryRepository deliveries,
            DeliveryAttemptRepository attempts,
            DeliveryClaimDao claimDao,
            DeliveryStatsDao stats,
            OutboxEventRepository outbox,
            ReplayRecordRepository replays,
            WebhookEndpointRepository endpoints,
            EndpointService endpointService) {
        this.events = events;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.claimDao = claimDao;
        this.stats = stats;
        this.outbox = outbox;
        this.replays = replays;
        this.endpoints = endpoints;
        this.endpointService = endpointService;
    }

    @Transactional(readOnly = true)
    public MetricsDtos.Summary summary(UUID tenantId, Duration window) {
        Instant now = Instant.now();
        Instant since = now.minus(window);

        DeliveryStatsDao.WindowSummary windowStats = stats.windowSummary(tenantId, since, now);

        long succeeded = deliveries.countByTenantIdAndStatus(tenantId, DeliveryStatus.SUCCESS);
        long failed = deliveries.countByTenantIdAndStatus(tenantId, DeliveryStatus.FAILED);
        long pending = deliveries.countByTenantIdAndStatus(tenantId, DeliveryStatus.PENDING);
        long retryScheduled =
                deliveries.countByTenantIdAndStatus(tenantId, DeliveryStatus.RETRY_SCHEDULED);
        long inProgress = deliveries.countByTenantIdAndStatus(tenantId, DeliveryStatus.IN_PROGRESS);

        long resolved = succeeded + failed;
        double successRate = resolved == 0 ? 0d : (double) succeeded / resolved;

        Instant oldestUnpublished = outbox.findOldestUnpublishedCreatedAt();
        MetricsDtos.QueueHealth queue =
                new MetricsDtos.QueueHealth(
                        claimDao.countDueBacklog(now),
                        claimDao.countInProgress(),
                        claimDao.oldestDueAgeSeconds(now),
                        outbox.countUnpublished(),
                        oldestUnpublished == null
                                ? 0d
                                : (double) Duration.between(oldestUnpublished, now).toMillis()
                                        / 1000d);

        return new MetricsDtos.Summary(
                events.countByTenantId(tenantId),
                events.countByTenantIdAndCreatedAtAfter(tenantId, since),
                deliveries.countByTenantId(tenantId),
                succeeded,
                failed,
                pending + retryScheduled,
                inProgress,
                failed,
                successRate,
                windowStats.avgDurationMs(),
                windowStats.p50Ms(),
                windowStats.p95Ms(),
                windowStats.p99Ms(),
                windowStats.attempts(),
                attempts.countRetriesSince(tenantId, since),
                replays.countByTenantIdAndCreatedAtAfter(tenantId, since),
                (int) endpoints.countByTenantIdAndDeletedAtIsNull(tenantId),
                queue,
                humanWindow(window),
                // Stated rather than implied: these come from histogram buckets.
                true,
                now);
    }

    /**
     * Downsamples the rollup to roughly 60 points, whatever the window length.
     *
     * <p>A fixed point budget rather than a fixed bucket width: a chart has a
     * fixed number of useful pixels, so sending 1 440 one-minute points for a
     * 24-hour view would be wasted bytes rendering into the same space.
     */
    @Transactional(readOnly = true)
    public MetricsDtos.Timeseries timeseries(UUID tenantId, Duration window) {
        Instant now = Instant.now();
        Instant from = now.minus(window);
        int bucketMinutes = Math.max(1, (int) Math.ceil(window.toMinutes() / 60.0));

        List<DeliveryStatsDao.TimeBucket> buckets =
                stats.timeseries(tenantId, from, now, bucketMinutes);

        return new MetricsDtos.Timeseries(
                humanWindow(window),
                bucketMinutes,
                buckets.stream()
                        .map(
                                bucket ->
                                        new MetricsDtos.TimeseriesPoint(
                                                bucket.start(),
                                                bucket.attempts(),
                                                bucket.successes(),
                                                bucket.retryableFailures(),
                                                bucket.terminalFailures(),
                                                bucket.avgDurationMs(),
                                                bucket.maxDurationMs()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<MetricsDtos.EndpointPerformance> endpointPerformance(UUID tenantId) {
        Map<UUID, EndpointDtos.HealthSummary> health = endpointService.healthByEndpoint(tenantId);
        return endpoints.findAllByTenant(tenantId).stream()
                .map(
                        endpoint -> {
                            EndpointDtos.HealthSummary summary =
                                    health.getOrDefault(
                                            endpoint.getId(), EndpointDtos.HealthSummary.empty());
                            return new MetricsDtos.EndpointPerformance(
                                    endpoint.getId(),
                                    endpoint.getName(),
                                    endpoint.getUrl(),
                                    summary.succeeded(),
                                    summary.failed(),
                                    summary.pending(),
                                    summary.successRate(),
                                    summary.state());
                        })
                .sorted(
                        // Worst first: the endpoint that needs attention should be
                        // the one at the top of the table.
                        java.util.Comparator.comparingDouble(
                                        MetricsDtos.EndpointPerformance::successRate)
                                .thenComparing(
                                        java.util.Comparator.comparingLong(
                                                        MetricsDtos.EndpointPerformance::failed)
                                                .reversed()))
                .toList();
    }

    /**
     * Formats a window the way the caller asked for it.
     *
     * <p>24 hours stays "24h" rather than becoming "1d": the API accepts "24h"
     * as a parameter, and echoing back a different string — even an equal one —
     * makes the UI look as though it answered a question nobody asked.
     */
    private static String humanWindow(Duration window) {
        long hours = window.toHours();
        if (hours > 24 && hours % 24 == 0) {
            return (hours / 24) + "d";
        }
        if (hours >= 1) {
            return hours + "h";
        }
        return window.toMinutes() + "m";
    }
}

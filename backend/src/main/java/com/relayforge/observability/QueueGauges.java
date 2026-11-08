package com.relayforge.observability;

import com.relayforge.delivery.DeliveryClaimDao;
import com.relayforge.outbox.OutboxClaimDao;
import com.relayforge.outbox.OutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes queue-depth and lag gauges to Prometheus.
 *
 * <h2>Why these are sampled on a schedule, not computed on scrape</h2>
 *
 * A Micrometer gauge normally evaluates its supplier at scrape time. Pointing
 * one straight at a {@code COUNT(*)} would mean every Prometheus scrape — and
 * every replica scraping the same instance — issues database queries, so the
 * monitoring load scales with the number of scrapers rather than with the
 * system. Worse, a slow database would make scrapes time out and the metrics
 * disappear exactly when they are most needed.
 *
 * <p>Instead the values are sampled once every 10 seconds into atomics, and the
 * gauges read those. Fixed, predictable database cost; metrics that keep working
 * when the database is struggling.
 */
@Component
public class QueueGauges {

    private static final Logger log = LoggerFactory.getLogger(QueueGauges.class);

    private final DeliveryClaimDao deliveryClaimDao;
    private final OutboxClaimDao outboxClaimDao;

    private final AtomicLong dueBacklog = new AtomicLong();
    private final AtomicLong inProgress = new AtomicLong();
    private final AtomicReference<Double> oldestDueAgeSeconds = new AtomicReference<>(0d);
    private final AtomicLong unpublishedOutbox = new AtomicLong();
    private final AtomicLong deadOutbox = new AtomicLong();
    private final AtomicReference<Double> oldestOutboxAgeSeconds = new AtomicReference<>(0d);

    public QueueGauges(
            DeliveryClaimDao deliveryClaimDao,
            OutboxClaimDao outboxClaimDao,
            MeterRegistry registry) {
        this.deliveryClaimDao = deliveryClaimDao;
        this.outboxClaimDao = outboxClaimDao;

        Gauge.builder("relayforge.queue.due_backlog", dueBacklog, AtomicLong::get)
                .description("Deliveries that are due now and not yet being attempted")
                .register(registry);
        Gauge.builder("relayforge.queue.in_progress", inProgress, AtomicLong::get)
                .description("Deliveries currently leased by a worker")
                .register(registry);
        Gauge.builder(
                        "relayforge.queue.oldest_due_age_seconds",
                        oldestDueAgeSeconds,
                        AtomicReference::get)
                .description(
                        "Age of the oldest due-but-unstarted delivery. The platform's real lag"
                                + " signal: what a user actually waits.")
                .register(registry);
        Gauge.builder("relayforge.outbox.unpublished", unpublishedOutbox, AtomicLong::get)
                .description("Outbox records not yet relayed to Kafka")
                .register(registry);
        Gauge.builder("relayforge.outbox.dead", deadOutbox, AtomicLong::get)
                .description("Outbox records parked after repeated publish failures")
                .register(registry);
        Gauge.builder(
                        "relayforge.outbox.oldest_age_seconds",
                        oldestOutboxAgeSeconds,
                        AtomicReference::get)
                .description("Age of the oldest unpublished outbox record")
                .register(registry);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    void sample() {
        try {
            Instant now = Instant.now();
            dueBacklog.set(deliveryClaimDao.countDueBacklog(now));
            inProgress.set(deliveryClaimDao.countInProgress());
            oldestDueAgeSeconds.set(deliveryClaimDao.oldestDueAgeSeconds(now));
            unpublishedOutbox.set(outboxClaimDao.countByStatus(OutboxStatus.PENDING));
            deadOutbox.set(outboxClaimDao.countByStatus(OutboxStatus.DEAD));
            oldestOutboxAgeSeconds.set(outboxClaimDao.oldestUnpublishedAgeSeconds(now));
        } catch (RuntimeException e) {
            // Keep the previous sample rather than zeroing the gauges: a reported
            // zero backlog would look like health, which is the opposite of the
            // truth when the database cannot be read.
            log.warn("Could not sample queue gauges; retaining the previous values", e);
        }
    }
}

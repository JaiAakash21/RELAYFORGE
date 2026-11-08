package com.relayforge.observability;

import com.relayforge.delivery.DeliveryClaimDao;
import com.relayforge.outbox.OutboxClaimDao;
import com.relayforge.outbox.OutboxStatus;
import java.time.Instant;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the delivery pipeline is actually moving.
 *
 * <h2>Why liveness is not enough</h2>
 *
 * The default health checks confirm that PostgreSQL, Redis and Kafka accept
 * connections. All three can be perfectly healthy while nothing is being
 * delivered — a crashed worker deployment, a consumer group stuck rebalancing, a
 * publisher that cannot reach the broker. Every dependency reports UP and the
 * backlog grows silently.
 *
 * <p>This indicator looks at the thing that matters instead: how long the oldest
 * ready delivery has been waiting, and whether the outbox is draining.
 *
 * <h2>Degraded rather than down</h2>
 *
 * A growing backlog reports {@code DEGRADED}, not {@code DOWN}. Reporting DOWN
 * would make an orchestrator restart or remove the instance from service — which
 * is precisely the wrong response to a backlog, since fewer workers makes it
 * worse. The distinction is deliberate: DOWN means "do not send me traffic",
 * while a backlog means "I need more capacity, not less".
 */
@Component
public class PipelineHealthIndicator implements HealthIndicator {

    /** Backlog age beyond which the pipeline is reported as degraded. */
    private static final double DUE_AGE_WARN_SECONDS = 60;

    /** Outbox age beyond which the relay is reported as degraded. */
    private static final double OUTBOX_AGE_WARN_SECONDS = 30;

    private final DeliveryClaimDao deliveryClaimDao;
    private final OutboxClaimDao outboxClaimDao;

    public PipelineHealthIndicator(
            DeliveryClaimDao deliveryClaimDao, OutboxClaimDao outboxClaimDao) {
        this.deliveryClaimDao = deliveryClaimDao;
        this.outboxClaimDao = outboxClaimDao;
    }

    @Override
    public Health health() {
        Instant now = Instant.now();
        try {
            long dueBacklog = deliveryClaimDao.countDueBacklog(now);
            double oldestDueAge = deliveryClaimDao.oldestDueAgeSeconds(now);
            long inProgress = deliveryClaimDao.countInProgress();
            long unpublished = outboxClaimDao.countByStatus(OutboxStatus.PENDING);
            long deadOutbox = outboxClaimDao.countByStatus(OutboxStatus.DEAD);
            double oldestOutboxAge = outboxClaimDao.oldestUnpublishedAgeSeconds(now);

            Health.Builder builder =
                    Health.up()
                            .withDetail("dueBacklog", dueBacklog)
                            .withDetail("oldestDueAgeSeconds", round(oldestDueAge))
                            .withDetail("inProgress", inProgress)
                            .withDetail("unpublishedOutboxRecords", unpublished)
                            .withDetail("oldestOutboxAgeSeconds", round(oldestOutboxAge))
                            .withDetail("deadOutboxRecords", deadOutbox);

            if (oldestDueAge > DUE_AGE_WARN_SECONDS) {
                return builder.status("DEGRADED")
                        .withDetail(
                                "reason",
                                "The oldest ready delivery has been waiting "
                                        + round(oldestDueAge)
                                        + "s. Workers may be down, scaled too low, or stuck.")
                        .build();
            }
            if (oldestOutboxAge > OUTBOX_AGE_WARN_SECONDS) {
                return builder.status("DEGRADED")
                        .withDetail(
                                "reason",
                                "The outbox is not draining: the oldest unpublished record is "
                                        + round(oldestOutboxAge)
                                        + "s old. Kafka may be unreachable.")
                        .build();
            }
            if (deadOutbox > 0) {
                return builder.status("DEGRADED")
                        .withDetail(
                                "reason",
                                deadOutbox
                                        + " outbox record(s) could not be published and have been"
                                        + " parked for inspection.")
                        .build();
            }
            return builder.build();
        } catch (RuntimeException e) {
            // Here DOWN is right: the check could not read the database at all.
            return Health.down()
                    .withDetail("reason", "Could not read pipeline state from the database")
                    .withException(e)
                    .build();
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

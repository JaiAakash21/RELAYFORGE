package com.relayforge.reconcile;

import com.relayforge.config.RelayForgeProperties;
import com.relayforge.config.WorkerRole;
import com.relayforge.delivery.DeliveryClaimDao;
import com.relayforge.observability.PipelineMetrics;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers deliveries that a dead worker left stranded in {@code IN_PROGRESS}.
 *
 * <h2>Why leases, rather than a timeout on the row</h2>
 *
 * A worker crash — OOM kill, rolling deploy, node failure — leaves no
 * opportunity to clean up. Something external has to notice. The lease makes
 * that detectable without any health-checking protocol between workers: a live
 * worker extends its lease, so an expired lease is positive evidence that
 * nobody is working on the delivery.
 *
 * <p>This is why a bare "stuck for more than N minutes" heuristic is not
 * equivalent. That cannot distinguish a dead worker from a live one talking to a
 * slow receiver, so it either reclaims work that is in flight (producing a real
 * duplicate delivery, needlessly) or waits so long that recovery is useless.
 *
 * <h2>The honest caveat</h2>
 *
 * If the worker died after the receiver processed the request but before the
 * result was written, recovery causes a second delivery of the same event. There
 * is no way to avoid this: the information needed to decide — whether the
 * receiver actually acted — was in the response that was lost. RelayForge
 * therefore makes it <em>recoverable</em> rather than claiming it cannot happen:
 *
 * <ul>
 *   <li>the attempt that was in flight is counted, so a crash loop cannot escape
 *       the attempt budget;
 *   <li>the delivery records that the outcome is genuinely unknown, rather than
 *       recording a failure that may not have happened;
 *   <li>every request carries a stable {@code X-RelayForge-Event-Id} so a
 *       receiver can deduplicate, which is the only place the ambiguity can
 *       actually be resolved.
 * </ul>
 *
 * This is at-least-once delivery, stated plainly rather than dressed up as
 * exactly-once.
 */
@Component
@WorkerRole
public class LeaseReconciler {

    private static final Logger log = LoggerFactory.getLogger(LeaseReconciler.class);

    /**
     * Deliveries recovered per cycle.
     *
     * <p>Bounded so that recovering a large backlog after a mass worker failure
     * is spread over several cycles instead of being enqueued as one spike that
     * would hit every receiver at once.
     */
    private static final int RECOVERY_BATCH_SIZE = 200;

    private final DeliveryClaimDao claimDao;
    private final PipelineMetrics metrics;
    private final Duration retryDelay;

    public LeaseReconciler(
            DeliveryClaimDao claimDao, PipelineMetrics metrics, RelayForgeProperties properties) {
        this.claimDao = claimDao;
        this.metrics = metrics;
        // A short delay, not the full backoff: this is not a receiver failure, so
        // the receiver does not need protecting from us. But not zero either —
        // immediate requeue during a rolling deploy would have the new workers
        // fight over the same rows as they start up.
        this.retryDelay = properties.retry().initialDelay();
    }

    @Scheduled(
            fixedDelayString = "${relayforge.outbox.reconcile-interval:PT15S}",
            initialDelayString = "PT20S")
    public void recoverExpiredLeases() {
        try {
            int recovered = reconcileOnce();
            if (recovered > 0) {
                log.warn(
                        "Recovered {} deliveries whose worker lease had expired. Each may have"
                                + " reached its receiver before the worker died; receivers should"
                                + " deduplicate on X-RelayForge-Event-Id.",
                        recovered);
            }
        } catch (RuntimeException e) {
            log.error("Lease reconciliation cycle failed", e);
        }
    }

    /** One reconciliation cycle. Returns how many deliveries were recovered. */
    public int reconcileOnce() {
        int recovered =
                claimDao.reclaimExpiredLeases(Instant.now(), retryDelay, RECOVERY_BATCH_SIZE);
        if (recovered > 0) {
            metrics.recordLeasesRecovered(recovered);
        }
        return recovered;
    }
}

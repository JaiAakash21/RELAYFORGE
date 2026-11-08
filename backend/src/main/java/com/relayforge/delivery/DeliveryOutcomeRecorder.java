package com.relayforge.delivery;

import com.relayforge.common.Ids;
import com.relayforge.common.InstanceIdentity;
import com.relayforge.common.Truncation;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.observability.DeliveryStatsDao;
import com.relayforge.observability.PipelineMetrics;
import com.relayforge.retry.BackoffCalculator;
import com.relayforge.retry.ResponseClassifier;
import com.relayforge.retry.RetryPolicy;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the result of one delivery attempt and decides what happens next.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * The attempt row, the per-minute rollup and the delivery's status transition
 * must commit together. Were they split, a crash between them would leave either
 * an attempt the scheduler does not know about, or a status change with no
 * recorded reason — and the delivery-detail screen would be showing a history
 * that contradicts the current state.
 *
 * <p>It lives outside {@link DeliveryExecutor} rather than as a
 * {@code @Transactional} method on it because Spring's transaction support is
 * proxy-based: a call from one method of a class to another method of the
 * <em>same</em> class does not pass through the proxy, so the annotation would be
 * silently ignored and the three writes would each auto-commit on their own.
 * That failure mode produces no error and no log line, which is exactly why the
 * boundary is made explicit here instead.
 */
@Service
public class DeliveryOutcomeRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeRecorder.class);

    private final DeliveryAttemptRepository attempts;
    private final DeliveryClaimDao claimDao;
    private final DeliveryStatsDao stats;
    private final PipelineMetrics metrics;
    private final BackoffCalculator backoff;
    private final Tracer tracer;
    private final String workerId;

    public DeliveryOutcomeRecorder(
            DeliveryAttemptRepository attempts,
            DeliveryClaimDao claimDao,
            DeliveryStatsDao stats,
            PipelineMetrics metrics,
            BackoffCalculator backoff,
            Tracer tracer,
            InstanceIdentity identity) {
        this.attempts = attempts;
        this.claimDao = claimDao;
        this.stats = stats;
        this.metrics = metrics;
        this.backoff = backoff;
        this.tracer = tracer;
        this.workerId = identity.id();
    }

    /** What the recorder decided to do with the delivery. */
    public enum Transition {
        SUCCEEDED,
        RETRY_SCHEDULED,
        DEAD_LETTERED
    }

    /**
     * Records the attempt and applies the resulting state transition.
     *
     * @return the transition that was applied
     */
    @Transactional
    public Transition record(
            Delivery delivery,
            WebhookEndpoint endpoint,
            RetryPolicy policy,
            int attemptNumber,
            AttemptKind kind,
            WebhookHttpSender.SendResult result,
            ResponseClassifier.Classification classification) {

        DeliveryAttempt attempt =
                new DeliveryAttempt(
                                Ids.newId(),
                                delivery.getId(),
                                delivery.getTenantId(),
                                attemptNumber)
                        .kind(kind)
                        .outcome(classification.outcome())
                        .responseStatus(result.status())
                        .responseBodyExcerpt(result.bodyExcerpt())
                        .responseHeadersExcerpt(result.headersExcerpt())
                        .errorMessage(
                                classification.outcome() == AttemptOutcome.SUCCESS
                                        ? null
                                        : Truncation.errorMessage(classification.summary()))
                        .errorClass(classification.errorClass())
                        .retryAfterSeconds(result.retryAfterSeconds())
                        .requestBytes(result.requestBytes())
                        .durationMs(result.durationMs())
                        .workerId(workerId)
                        .traceId(currentTraceId())
                        .startedAt(result.startedAt())
                        .completedAt(result.completedAt());
        attempts.save(attempt);

        stats.recordAttempt(
                delivery.getTenantId(),
                result.startedAt(),
                classification.outcome(),
                result.durationMs());
        metrics.recordAttempt(
                classification.outcome(),
                result.status(),
                classification.errorClass(),
                result.durationMs());

        Instant now = Instant.now();

        if (classification.outcome() == AttemptOutcome.SUCCESS) {
            claimDao.completeSuccess(
                    delivery.getId(), workerId, attemptNumber, result.status(), now);
            return Transition.SUCCEEDED;
        }

        boolean budgetExhausted = attemptNumber >= delivery.getMaxAttempts();
        if (!classification.retryable() || budgetExhausted) {
            // Distinguish "we gave up" from "retrying was never going to work":
            // the dead-letter screen shows this text, and the two cases call for
            // different action from whoever reads it.
            String reason =
                    budgetExhausted && classification.retryable()
                            ? "Attempt budget exhausted after "
                                    + attemptNumber
                                    + " attempts. Last failure: "
                                    + classification.summary()
                            : classification.summary();
            claimDao.failTerminally(
                    delivery.getId(),
                    workerId,
                    attemptNumber,
                    result.status(),
                    Truncation.errorMessage(reason),
                    classification.errorClass(),
                    now);
            metrics.recordDeadLettered();
            log.info(
                    "Delivery {} dead-lettered after {} attempt(s): {}",
                    delivery.getId(),
                    attemptNumber,
                    classification.errorClass());
            return Transition.DEAD_LETTERED;
        }

        Duration delay =
                backoff.resolveDelay(attemptNumber + 1, policy, result.retryAfterSeconds());
        claimDao.scheduleRetry(
                delivery.getId(),
                workerId,
                attemptNumber,
                result.status(),
                Truncation.errorMessage(classification.summary()),
                classification.errorClass(),
                now.plus(delay),
                now);
        metrics.recordRetryScheduled();
        log.debug(
                "Delivery {} attempt {} failed ({}); next attempt in {} ms",
                delivery.getId(),
                attemptNumber,
                classification.errorClass(),
                delay.toMillis());
        return Transition.RETRY_SCHEDULED;
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}

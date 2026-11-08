package com.relayforge.delivery;

import com.relayforge.config.RelayForgeProperties;
import com.relayforge.config.WorkerRole;
import com.relayforge.messaging.DeliveryRequest;
import com.relayforge.messaging.Topics;
import com.relayforge.observability.PipelineMetrics;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enqueues deliveries that are due to be attempted.
 *
 * <h2>Why retries are scheduled in the database rather than in Kafka</h2>
 *
 * Kafka has no native per-message delay. The usual workarounds are a ladder of
 * delay topics (one per backoff tier) or re-consuming with a sleep. Both are
 * awkward here:
 *
 * <ul>
 *   <li>Delay topics quantise the schedule to whatever tiers exist, so a
 *       computed backoff of 7.3 s becomes "the 10 s topic" — and jitter, the
 *       whole point of which is fine-grained spreading, stops working.
 *   <li>Sleeping in a consumer holds a partition hostage, blocking every other
 *       delivery behind one endpoint's backoff.
 *   <li>Neither lets a user see or change a scheduled retry, and the UI has to
 *       show "next attempt in 2m 14s" from somewhere.
 * </ul>
 *
 * So {@code next_attempt_at} lives on the delivery row — where it is queryable,
 * displayable and correctable — and this component turns "due" into "queued".
 * Kafka does what Kafka is good at: fanning bounded work out to consumers.
 *
 * <h2>It is also the recovery path</h2>
 *
 * The same query picks up any due delivery whose {@code queued_at} is older than
 * the requeue grace period. That single condition covers every way a Kafka
 * message can be lost: the broker dropped it, the publisher died after claiming,
 * a consumer crashed before acknowledging, a topic was recreated. Nothing stays
 * stuck waiting for a message that is never coming — the database is the source
 * of truth, so the database is what decides what still needs doing.
 */
@Component
@WorkerRole
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final DeliveryClaimDao claimDao;
    private final KafkaTemplate<String, DeliveryRequest> kafkaTemplate;
    private final RelayForgeProperties.Outbox config;
    private final PipelineMetrics metrics;

    public DeliveryDispatcher(
            DeliveryClaimDao claimDao,
            KafkaTemplate<String, DeliveryRequest> kafkaTemplate,
            RelayForgeProperties properties,
            PipelineMetrics metrics) {
        this.claimDao = claimDao;
        this.kafkaTemplate = kafkaTemplate;
        this.config = properties.outbox();
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${relayforge.outbox.dispatch-interval:PT1S}",
            initialDelayString = "PT3S")
    public void dispatchDue() {
        try {
            dispatchOnce();
        } catch (RuntimeException e) {
            log.error("Delivery dispatch cycle failed", e);
        }
    }

    /** One dispatch cycle. Returns how many deliveries were enqueued. */
    public int dispatchOnce() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(config.requeueGrace());

        List<DeliveryClaimDao.DispatchableDelivery> due =
                claimDao.claimDueForDispatch(now, staleBefore, config.dispatchBatchSize());
        if (due.isEmpty()) {
            return 0;
        }

        int enqueued = 0;
        for (DeliveryClaimDao.DispatchableDelivery delivery : due) {
            try {
                DeliveryRequest message =
                        new DeliveryRequest(
                                delivery.id(),
                                delivery.tenantId(),
                                delivery.eventId(),
                                delivery.endpointId(),
                                delivery.attemptCount(),
                                System.currentTimeMillis());
                // Partition by endpoint so one endpoint's work is serialised.
                // Fire-and-forget is safe here, unlike in the outbox: queued_at was
                // already advanced, so a lost send is simply re-dispatched after
                // the grace period rather than lost.
                kafkaTemplate.send(
                        Topics.DELIVERY_REQUESTS, delivery.endpointId().toString(), message);
                enqueued++;
            } catch (RuntimeException e) {
                log.warn("Could not enqueue delivery {}", delivery.id(), e);
            }
        }

        if (enqueued > 0) {
            log.debug("Dispatched {} due deliveries", enqueued);
        }
        return enqueued;
    }
}

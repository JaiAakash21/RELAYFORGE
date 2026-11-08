package com.relayforge.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayforge.config.RelayForgeProperties;
import com.relayforge.messaging.DeliveryRequest;
import com.relayforge.observability.PipelineMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.relayforge.config.WorkerRole;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays outbox rows to Kafka.
 *
 * <h2>The sequence, and why it is in this order</h2>
 *
 * <ol>
 *   <li><b>Claim a batch</b> atomically ({@code FOR UPDATE SKIP LOCKED}), writing
 *       a claim with an expiry. Several publisher instances can run concurrently
 *       and will never claim the same row.
 *   <li><b>Publish and wait for the broker's acknowledgement.</b> Synchronously,
 *       per record. Fire-and-forget here would let step 3 mark rows published
 *       that the broker never durably stored.
 *   <li><b>Mark published</b> — only the rows that were actually acknowledged.
 * </ol>
 *
 * <h2>The failure this design exists to survive</h2>
 *
 * The dangerous window is between a successful publish and the database update.
 * If the process dies there, Kafka holds the message but the row still says
 * {@code CLAIMED}. The claim expires, another publisher takes the row, and the
 * message is published a <em>second</em> time.
 *
 * <p>That duplicate is accepted deliberately rather than engineered away. The
 * alternative — a distributed transaction across PostgreSQL and Kafka — is
 * expensive, fragile, and still not actually exactly-once end to end once an
 * external HTTP call is involved. Instead the consumer is made idempotent (a
 * conditional {@code UPDATE} on the delivery row), which makes a duplicate
 * message cost one no-op statement. Choosing "at-least-once plus idempotent
 * consumption" over "exactly-once" is the central trade-off of the whole
 * pipeline; it is documented in docs/ENGINEERING_DECISIONS.md.
 *
 * <h2>Role gating</h2>
 *
 * Runs only when this process has the worker role. If every API instance also
 * published, they would all poll the same table, and the API's latency would be
 * coupled to the broker's health for no benefit.
 */
@Component
@WorkerRole
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxClaimDao claimDao;
    private final KafkaTemplate<String, DeliveryRequest> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RelayForgeProperties.Outbox config;
    private final PipelineMetrics metrics;
    private final OutboxEventRepository repository;
    private final String instanceId;

    public OutboxPublisher(
            OutboxClaimDao claimDao,
            OutboxEventRepository repository,
            KafkaTemplate<String, DeliveryRequest> kafkaTemplate,
            ObjectMapper objectMapper,
            RelayForgeProperties properties,
            PipelineMetrics metrics,
            com.relayforge.common.InstanceIdentity identity) {
        this.claimDao = claimDao;
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.config = properties.outbox();
        this.metrics = metrics;
        this.instanceId = identity.id();
    }

    /**
     * One publish cycle.
     *
     * <p>{@code fixedDelayString} rather than a fixed rate: cycles must not
     * overlap. A fixed rate would start a new poll while the previous one was
     * still blocked on a slow broker, piling up work and inflating claim
     * contention exactly when the system is least healthy.
     */
    @Scheduled(
            fixedDelayString = "${relayforge.outbox.poll-interval:PT1S}",
            initialDelayString = "PT2S")
    public void publishPending() {
        try {
            drain();
        } catch (RuntimeException e) {
            // Never let an exception escape a scheduled method: Spring would stop
            // rescheduling it and the relay would silently die.
            log.error("Outbox publish cycle failed", e);
        }
    }

    /** Publishes one claimed batch. Returns how many rows were acknowledged. */
    public int drain() {
        Instant now = Instant.now();
        List<OutboxClaimDao.ClaimedRecord> claimed =
                claimDao.claimBatch(instanceId, now, config.claimTtl(), config.batchSize());
        if (claimed.isEmpty()) {
            return 0;
        }

        List<UUID> acknowledged = new ArrayList<>(claimed.size());
        for (OutboxClaimDao.ClaimedRecord record : claimed) {
            try {
                DeliveryRequest message =
                        objectMapper.treeToValue(record.payload(), DeliveryRequest.class);

                // Block on the broker acknowledgement. This is what makes step 3
                // honest: we only ever record "published" for something the
                // broker confirmed it has.
                SendResult<String, DeliveryRequest> result =
                        kafkaTemplate
                                .send(record.topic(), record.partitionKey(), message)
                                .get(20, TimeUnit.SECONDS);

                if (result != null && result.getRecordMetadata() != null) {
                    acknowledged.add(record.id());
                    metrics.recordOutboxPublished();
                } else {
                    releaseWithError(record, "Broker returned no record metadata");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseWithError(record, "Publishing was interrupted");
                break;
            } catch (Exception e) {
                // Includes the serialisation case: a row whose payload cannot be
                // turned into a message will never publish, and the attempt
                // counter will eventually park it as DEAD rather than looping.
                releaseWithError(record, describe(e));
            }
        }

        if (!acknowledged.isEmpty()) {
            try {
                claimDao.markPublished(acknowledged, Instant.now());
            } catch (RuntimeException e) {
                // The exact "published to Kafka but the database update failed"
                // case. Logged loudly, then left alone: the claims expire and the
                // rows are republished, which the idempotent consumer absorbs.
                log.error(
                        "Published {} outbox records to Kafka but could not mark them published."
                                + " Their claims will expire and they will be republished;"
                                + " duplicate messages are expected and are absorbed by the"
                                + " idempotent delivery claim.",
                        acknowledged.size(),
                        e);
                metrics.recordOutboxAckFailure(acknowledged.size());
            }
        }
        return acknowledged.size();
    }

    private void releaseWithError(OutboxClaimDao.ClaimedRecord record, String error) {
        metrics.recordOutboxPublishFailure();
        log.warn(
                "Failed to publish outbox record {} (attempt {}): {}",
                record.id(),
                record.attemptCount(),
                error);
        try {
            claimDao.releaseWithError(
                    record.id(),
                    com.relayforge.common.Truncation.errorMessage(error),
                    config.maxPublishAttempts());
        } catch (RuntimeException e) {
            log.error("Could not release the claim on outbox record {}", record.id(), e);
        }
    }

    /**
     * Prunes published rows.
     *
     * <p>The outbox is a relay buffer, not an archive — the event and delivery
     * tables are the permanent record. Left unpruned the table grows without
     * bound, and the partial index the publisher depends on would be the only
     * thing keeping its query fast while the heap grew forever.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    @org.springframework.transaction.annotation.Transactional
    public void prunePublished() {
        try {
            Instant cutoff = Instant.now().minus(config.retainPublishedFor());
            int deleted = repository.deletePublishedBefore(cutoff);
            if (deleted > 0) {
                log.info("Pruned {} published outbox records older than {}", deleted, cutoff);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox pruning cycle failed", e);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}

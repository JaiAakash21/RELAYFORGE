package com.relayforge.deadletter;

import com.relayforge.common.ConflictException;
import com.relayforge.common.Ids;
import com.relayforge.common.NotFoundException;
import com.relayforge.common.ValidationException;
import com.relayforge.config.RelayForgeProperties;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryClaimDao;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.observability.PipelineMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays dead-lettered deliveries.
 *
 * <h2>What replay does — and deliberately does not do</h2>
 *
 * Replay moves a {@code FAILED} (or {@code CANCELLED}) delivery back to
 * {@code PENDING} and grants it a fresh budget of attempts. It does <em>not</em>:
 *
 * <ul>
 *   <li><b>Reset {@code attempt_count}.</b> The new budget is
 *       {@code attempt_count + extraAttempts}, so attempt numbering keeps
 *       marching forward. Attempt 4 after a replay is still attempt 4, and it
 *       sorts correctly beneath attempts 1–3 in the timeline.
 *   <li><b>Delete the previous attempts.</b> Those rows are the evidence of why
 *       the delivery dead-lettered. Erasing them would make a delivery that
 *       succeeded on its third replay look identical to one that worked first
 *       time, hiding a persistently broken receiver.
 *   <li><b>Create a new event or a new delivery.</b> The same delivery is
 *       reopened, so {@code X-RelayForge-Event-Id} and
 *       {@code X-RelayForge-Delivery-Id} are unchanged and a receiver's
 *       deduplication still works. Replay is "try this again", not "send
 *       something new".
 * </ul>
 *
 * <h2>Concurrent replay</h2>
 *
 * Two operators clicking replay at the same moment produce exactly one replay.
 * The reopen is a conditional {@code UPDATE} guarded on the status still being
 * replayable, so the second caller updates zero rows and is told the delivery is
 * no longer in a replayable state — rather than granting a double budget and
 * writing two audit records for one logical action.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    /** Ceiling on one bulk replay, so a single click cannot enqueue unbounded work. */
    private static final int MAX_BULK_REPLAY = 500;

    private final DeliveryRepository deliveries;
    private final DeliveryClaimDao claimDao;
    private final ReplayRecordRepository replayRecords;
    private final PipelineMetrics metrics;
    private final int extraAttempts;

    public ReplayService(
            DeliveryRepository deliveries,
            DeliveryClaimDao claimDao,
            ReplayRecordRepository replayRecords,
            PipelineMetrics metrics,
            RelayForgeProperties properties) {
        this.deliveries = deliveries;
        this.claimDao = claimDao;
        this.replayRecords = replayRecords;
        this.metrics = metrics;
        this.extraAttempts = properties.retry().replayExtraAttempts();
    }

    /** Replays one delivery. */
    @Transactional
    public DeliveryDtos.ReplayResponse replay(
            UUID tenantId, UUID deliveryId, UUID requestedBy, String reason) {
        return replayInternal(tenantId, deliveryId, requestedBy, reason, null, true);
    }

    /**
     * Replays several deliveries under one batch id.
     *
     * <p>Individual failures do not abort the batch: an operator replaying 200
     * dead letters wants the 199 valid ones to proceed, with the one that had
     * already been replayed reported as skipped.
     */
    @Transactional
    public DeliveryDtos.BulkReplayResponse replayBatch(
            UUID tenantId, List<UUID> deliveryIds, UUID requestedBy, String reason) {

        if (deliveryIds == null || deliveryIds.isEmpty()) {
            throw new ValidationException("At least one delivery id is required");
        }
        List<UUID> distinct = deliveryIds.stream().distinct().toList();
        if (distinct.size() > MAX_BULK_REPLAY) {
            throw new ValidationException(
                    "batch_too_large",
                    "A bulk replay may contain at most "
                            + MAX_BULK_REPLAY
                            + " deliveries (received "
                            + distinct.size()
                            + ")");
        }

        UUID batchId = Ids.newId();
        List<DeliveryDtos.ReplayResponse> results = new ArrayList<>(distinct.size());
        int accepted = 0;
        for (UUID deliveryId : distinct) {
            DeliveryDtos.ReplayResponse result =
                    replayInternal(tenantId, deliveryId, requestedBy, reason, batchId, false);
            results.add(result);
            if (result.accepted()) {
                accepted++;
            }
        }
        log.info(
                "Bulk replay {} for tenant {}: {} accepted, {} skipped",
                batchId,
                tenantId,
                accepted,
                distinct.size() - accepted);
        return new DeliveryDtos.BulkReplayResponse(
                batchId, distinct.size(), accepted, distinct.size() - accepted, results);
    }

    /** Replays every dead letter for one endpoint. */
    @Transactional
    public DeliveryDtos.BulkReplayResponse replayAllForEndpoint(
            UUID tenantId, UUID endpointId, UUID requestedBy, String reason) {
        List<UUID> ids =
                deliveries.findAllDeadLettersForEndpoint(tenantId, endpointId).stream()
                        .map(Delivery::getId)
                        .limit(MAX_BULK_REPLAY)
                        .toList();
        if (ids.isEmpty()) {
            return new DeliveryDtos.BulkReplayResponse(Ids.newId(), 0, 0, 0, List.of());
        }
        return replayBatch(tenantId, ids, requestedBy, reason);
    }

    /**
     * @param strict when true an ineligible delivery raises; when false it is
     *     reported as skipped so a batch can continue
     */
    private DeliveryDtos.ReplayResponse replayInternal(
            UUID tenantId,
            UUID deliveryId,
            UUID requestedBy,
            String reason,
            UUID batchId,
            boolean strict) {

        Delivery delivery =
                deliveries
                        .findByIdAndTenantId(deliveryId, tenantId)
                        .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        if (!delivery.getStatus().isReplayable()) {
            String message =
                    "Only failed or cancelled deliveries can be replayed; this one is "
                            + delivery.getStatus();
            if (strict) {
                throw new ConflictException("not_replayable", message);
            }
            return new DeliveryDtos.ReplayResponse(
                    deliveryId, false, delivery.getStatus().name(), message);
        }

        // Capture the pre-replay state before the UPDATE changes it, so the audit
        // record describes what was actually replayed.
        String previousStatus = delivery.getStatus().name();
        int attemptsBefore = delivery.getAttemptCount();

        boolean reopened =
                claimDao.reopenForReplay(deliveryId, tenantId, extraAttempts, Instant.now());
        if (!reopened) {
            // Lost the race with a concurrent replay. Reported, not retried: the
            // delivery is already live, which is what the caller wanted.
            String message =
                    "This delivery was replayed concurrently by another request and is already"
                            + " queued";
            if (strict) {
                throw new ConflictException("replay_in_progress", message);
            }
            return new DeliveryDtos.ReplayResponse(deliveryId, false, "PENDING", message);
        }

        replayRecords.save(
                new ReplayRecord(
                        Ids.newId(),
                        tenantId,
                        deliveryId,
                        requestedBy,
                        com.relayforge.common.Truncation.errorMessage(reason),
                        previousStatus,
                        attemptsBefore,
                        batchId));
        metrics.recordReplay(1);

        // No Kafka publish here. The delivery is PENDING with next_attempt_at set
        // to now, so the dispatcher enqueues it on its next poll — one code path
        // for "work that is due", whether it became due by backoff or by replay.
        return new DeliveryDtos.ReplayResponse(
                deliveryId,
                true,
                "PENDING",
                "Replay accepted. The delivery is queued and will be attempted shortly; the"
                        + " previous attempts remain in its history.");
    }

    @Transactional(readOnly = true)
    public List<DeliveryDtos.ReplayRecordResponse> history(UUID tenantId) {
        return replayRecords.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(
                        record ->
                                new DeliveryDtos.ReplayRecordResponse(
                                        record.getId(),
                                        record.getCreatedAt(),
                                        record.getPreviousStatus(),
                                        record.getAttemptsBefore(),
                                        record.getReason(),
                                        record.getRequestedBy(),
                                        record.getBatchId()))
                .toList();
    }
}

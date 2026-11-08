package com.relayforge.deadletter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit record for one manual replay.
 *
 * <p>Replay is the one operation that changes a terminal delivery back into live
 * work, so it is recorded explicitly: who asked, when, what the delivery's state
 * was beforehand, and how many attempts had already been made. Without this row
 * a delivery that eventually succeeds after three replays looks identical to one
 * that succeeded first time, which would quietly hide a persistent problem with
 * a receiver.
 */
@Entity
@Table(name = "replay_records")
public class ReplayRecord {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "requested_via", nullable = false, length = 20)
    private String requestedVia = "API";

    @Column(length = 500)
    private String reason;

    @Column(name = "previous_status", nullable = false, length = 20)
    private String previousStatus;

    @Column(name = "attempts_before", nullable = false)
    private int attemptsBefore;

    /** Groups the rows produced by one bulk replay, so a batch can be audited as a unit. */
    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReplayRecord() {}

    public ReplayRecord(
            UUID id,
            UUID tenantId,
            UUID deliveryId,
            UUID requestedBy,
            String reason,
            String previousStatus,
            int attemptsBefore,
            UUID batchId) {
        this.id = id;
        this.tenantId = tenantId;
        this.deliveryId = deliveryId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.previousStatus = previousStatus;
        this.attemptsBefore = attemptsBefore;
        this.batchId = batchId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getRequestedVia() {
        return requestedVia;
    }

    public String getReason() {
        return reason;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public int getAttemptsBefore() {
        return attemptsBefore;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

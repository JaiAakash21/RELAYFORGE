package com.relayforge.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One (event, endpoint) pair: the unit of work a delivery worker claims, and the
 * authoritative record of whether that webhook ever arrived.
 *
 * <h2>Concurrency</h2>
 *
 * The important state transitions on this entity are <em>not</em> performed
 * through this class. They are performed by conditional {@code UPDATE}
 * statements in {@link DeliveryRepository} — for example "move to IN_PROGRESS
 * only if the row is still claimable". That is what makes two workers racing on
 * the same Kafka message safe: the database decides the winner in one atomic
 * statement, and the loser observes zero updated rows and stops.
 *
 * <p>The JPA entity is therefore used for reads, for creation, and for the
 * post-attempt bookkeeping that happens while the worker provably holds the
 * lease. The {@code @Version} column is a second line of defence for those
 * entity-mediated writes.
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    /**
     * Snapshot of the endpoint's attempt budget, taken at creation.
     *
     * <p>Copied rather than read live so that changing an endpoint's retry policy
     * cannot retroactively dead-letter (or resurrect) deliveries that are already
     * in flight. A delivery is judged against the rules that applied when it was
     * created.
     */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** When this delivery was last handed to Kafka. See the dispatcher. */
    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "lease_owner", length = 80)
    private String leaseOwner;

    @Column(name = "last_response_code")
    private Integer lastResponseCode;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_class", length = 40)
    private ErrorClass lastErrorClass;

    @Column(name = "first_attempt_at")
    private Instant firstAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected Delivery() {}

    public Delivery(
            UUID id,
            UUID tenantId,
            UUID eventId,
            UUID endpointId,
            int maxAttempts,
            Instant queuedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.endpointId = endpointId;
        this.maxAttempts = maxAttempts;
        this.status = DeliveryStatus.PENDING;
        // Set at creation because the outbox row written in the same transaction
        // is about to enqueue this delivery. A NULL here would make the recovery
        // dispatcher treat a perfectly healthy new delivery as a lost message.
        this.queuedAt = queuedAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** True when the attempt budget has been spent. */
    public boolean isExhausted() {
        return attemptCount >= maxAttempts;
    }

    public int attemptsRemaining() {
        return Math.max(0, maxAttempts - attemptCount);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public int getReplayCount() {
        return replayCount;
    }

    public void setReplayCount(int replayCount) {
        this.replayCount = replayCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant v) {
        this.nextAttemptAt = v;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Instant v) {
        this.queuedAt = v;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant v) {
        this.leaseUntil = v;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String v) {
        this.leaseOwner = v;
    }

    public Integer getLastResponseCode() {
        return lastResponseCode;
    }

    public void setLastResponseCode(Integer v) {
        this.lastResponseCode = v;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String v) {
        this.lastError = v;
    }

    public ErrorClass getLastErrorClass() {
        return lastErrorClass;
    }

    public void setLastErrorClass(ErrorClass v) {
        this.lastErrorClass = v;
    }

    public Instant getFirstAttemptAt() {
        return firstAttemptAt;
    }

    public void setFirstAttemptAt(Instant v) {
        this.firstAttemptAt = v;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant v) {
        this.completedAt = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}

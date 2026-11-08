package com.relayforge.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * A message that must reach Kafka, written in the same database transaction as
 * the state change it describes.
 *
 * <h2>Why this table exists</h2>
 *
 * Ingestion has to do two things: persist an event with its deliveries, and tell
 * the workers about it. Those live in two different systems, so there is no
 * transaction that spans both. Doing them in sequence is broken in both orders:
 *
 * <ul>
 *   <li>commit, then publish — the process can die in between, and the event is
 *       accepted but silently never delivered;
 *   <li>publish, then commit — the commit can fail, and workers chase a delivery
 *       row that does not exist.
 * </ul>
 *
 * The outbox removes the choice. The only write ingestion performs is to
 * PostgreSQL, so "accepted" and "will be relayed" become the same atomic fact.
 * A separate publisher then moves rows to Kafka, and because it can crash at any
 * point, it may publish a row twice. Duplicates are expected by design and
 * absorbed downstream by the conditional delivery claim.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 120)
    private String topic;

    /**
     * Kafka partition key.
     *
     * <p>Set to the endpoint id so that every delivery destined for one endpoint
     * lands on one partition and is therefore processed in order by one consumer.
     * That keeps a single slow receiver from being hammered by parallel attempts
     * for different events, and makes per-endpoint ordering an emergent property
     * of the partitioning rather than something to coordinate.
     */
    @Column(name = "partition_key", nullable = false, length = 120)
    private String partitionKey;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claimed_by", length = 80)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    /** W3C traceparent captured at write time, so the relay joins the same trace. */
    @Column(name = "trace_parent", length = 64)
    private String traceParent;

    protected OutboxEvent() {}

    public OutboxEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            UUID tenantId,
            String eventType,
            String topic,
            String partitionKey,
            JsonNode payload,
            String traceParent) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.traceParent = traceParent;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }
}

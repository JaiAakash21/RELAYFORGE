package com.relayforge.events;

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
 * An immutable record of something a tenant published.
 *
 * <p>There is no update path: an event is written once, during ingestion, and
 * read thereafter. Everything that changes about an event — whether it was
 * delivered, how many times we tried — lives on {@code deliveries}, not here.
 * That separation is what makes the event table append-only and trivially
 * cacheable.
 */
@Entity
@Table(name = "events")
public class Event {

    /** How the event entered the system. Drives filtering and demo labelling. */
    public enum Source {
        API,
        PLAYGROUND,
        ENDPOINT_TEST,
        SEED
    }

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Source source = Source.API;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "payload_bytes", nullable = false)
    private int payloadBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Event() {}

    public Event(
            UUID id,
            UUID tenantId,
            String eventType,
            JsonNode payload,
            String idempotencyKey,
            Source source,
            UUID apiKeyId,
            int payloadBytes) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.source = source;
        this.apiKeyId = apiKeyId;
        this.payloadBytes = payloadBytes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Source getSource() {
        return source;
    }

    public UUID getApiKeyId() {
        return apiKeyId;
    }

    public int getPayloadBytes() {
        return payloadBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

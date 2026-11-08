package com.relayforge.playground;

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
 * One supervised fault-injection experiment in the Reliability Playground.
 *
 * <p>A run ties together the scenario that was chosen, the parameters it was
 * given, the endpoint that was pointed at the simulator, the event that was
 * published and the delivery that resulted. The UI reads a run and then reads
 * that delivery's real attempt history — so what the timeline shows is the
 * backend's actual recorded behaviour, not a client-side animation of what the
 * backend is assumed to be doing.
 */
@Entity
@Table(name = "playground_runs")
public class PlaygroundRun {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        DEAD_LETTERED,
        CANCELLED
    }

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "scenario_id", nullable = false, length = 60)
    private String scenarioId;

    @Column(name = "scenario_name", nullable = false, length = 120)
    private String scenarioName;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode config;

    @Column(name = "simulator_token", nullable = false, length = 80, unique = true)
    private String simulatorToken;

    @Column(name = "endpoint_id")
    private UUID endpointId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "delivery_id")
    private UUID deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RUNNING;

    @Column(name = "outcome_note", length = 500)
    private String outcomeNote;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PlaygroundRun() {}

    public PlaygroundRun(
            UUID id,
            UUID tenantId,
            String scenarioId,
            String scenarioName,
            JsonNode config,
            String simulatorToken,
            UUID createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.scenarioId = scenarioId;
        this.scenarioName = scenarioName;
        this.config = config;
        this.simulatorToken = simulatorToken;
        this.createdBy = createdBy;
    }

    public void complete(Status status, String note) {
        this.status = status;
        this.outcomeNote = note;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public JsonNode getConfig() {
        return config;
    }

    public String getSimulatorToken() {
        return simulatorToken;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(UUID v) {
        this.endpointId = v;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID v) {
        this.eventId = v;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(UUID v) {
        this.deliveryId = v;
    }

    public Status getStatus() {
        return status;
    }

    public String getOutcomeNote() {
        return outcomeNote;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

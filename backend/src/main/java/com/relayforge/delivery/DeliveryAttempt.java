package com.relayforge.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An append-only record of one outbound HTTP attempt.
 *
 * <p>This table is the system's evidence. Nothing deletes from it and nothing
 * updates it — not even a replay, which appends new attempts with higher numbers
 * rather than overwriting the failures that led to the dead letter. When a user
 * asks "what actually happened to my webhook?", the answer is this table.
 */
@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptKind kind = AttemptKind.AUTOMATIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptOutcome outcome;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body_excerpt", length = 2048)
    private String responseBodyExcerpt;

    @Column(name = "response_headers_excerpt", length = 2048)
    private String responseHeadersExcerpt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_class", length = 40)
    private ErrorClass errorClass;

    @Column(name = "retry_after_seconds")
    private Integer retryAfterSeconds;

    @Column(name = "request_bytes")
    private Integer requestBytes;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "worker_id", length = 80)
    private String workerId;

    /** Links this attempt to the distributed trace that produced it. */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected DeliveryAttempt() {}

    // Built through the fluent setters below from DeliveryWorker; a constructor
    // with sixteen parameters would be far easier to call incorrectly.
    public DeliveryAttempt(UUID id, UUID deliveryId, UUID tenantId, int attemptNumber) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.tenantId = tenantId;
        this.attemptNumber = attemptNumber;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptKind getKind() {
        return kind;
    }

    public DeliveryAttempt kind(AttemptKind v) {
        this.kind = v;
        return this;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public DeliveryAttempt outcome(AttemptOutcome v) {
        this.outcome = v;
        return this;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public DeliveryAttempt responseStatus(Integer v) {
        this.responseStatus = v;
        return this;
    }

    public String getResponseBodyExcerpt() {
        return responseBodyExcerpt;
    }

    public DeliveryAttempt responseBodyExcerpt(String v) {
        this.responseBodyExcerpt = v;
        return this;
    }

    public String getResponseHeadersExcerpt() {
        return responseHeadersExcerpt;
    }

    public DeliveryAttempt responseHeadersExcerpt(String v) {
        this.responseHeadersExcerpt = v;
        return this;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public DeliveryAttempt errorMessage(String v) {
        this.errorMessage = v;
        return this;
    }

    public ErrorClass getErrorClass() {
        return errorClass;
    }

    public DeliveryAttempt errorClass(ErrorClass v) {
        this.errorClass = v;
        return this;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public DeliveryAttempt retryAfterSeconds(Integer v) {
        this.retryAfterSeconds = v;
        return this;
    }

    public Integer getRequestBytes() {
        return requestBytes;
    }

    public DeliveryAttempt requestBytes(Integer v) {
        this.requestBytes = v;
        return this;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public DeliveryAttempt durationMs(int v) {
        this.durationMs = v;
        return this;
    }

    public String getWorkerId() {
        return workerId;
    }

    public DeliveryAttempt workerId(String v) {
        this.workerId = v;
        return this;
    }

    public String getTraceId() {
        return traceId;
    }

    public DeliveryAttempt traceId(String v) {
        this.traceId = v;
        return this;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public DeliveryAttempt startedAt(Instant v) {
        this.startedAt = v;
        return this;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public DeliveryAttempt completedAt(Instant v) {
        this.completedAt = v;
        return this;
    }
}

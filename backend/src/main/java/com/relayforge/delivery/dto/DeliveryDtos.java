package com.relayforge.delivery.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryAttempt;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for deliveries, attempts and replay. */
public final class DeliveryDtos {

    private DeliveryDtos() {}

    public record Response(
            UUID id,
            UUID eventId,
            UUID endpointId,
            String endpointName,
            String endpointUrl,
            String eventType,
            String status,
            int attemptCount,
            int maxAttempts,
            int attemptsRemaining,
            int replayCount,
            Instant nextAttemptAt,
            Instant firstAttemptAt,
            Instant completedAt,
            Integer lastResponseCode,
            String lastError,
            String lastErrorClass,
            /** Set while a worker holds the lease — useful when diagnosing a stall. */
            Instant leaseUntil,
            String leaseOwner,
            boolean replayable,
            Instant createdAt,
            Instant updatedAt) {

        public static Response from(
                Delivery delivery, String endpointName, String endpointUrl, String eventType) {
            return new Response(
                    delivery.getId(),
                    delivery.getEventId(),
                    delivery.getEndpointId(),
                    endpointName,
                    endpointUrl,
                    eventType,
                    delivery.getStatus().name(),
                    delivery.getAttemptCount(),
                    delivery.getMaxAttempts(),
                    delivery.attemptsRemaining(),
                    delivery.getReplayCount(),
                    delivery.getNextAttemptAt(),
                    delivery.getFirstAttemptAt(),
                    delivery.getCompletedAt(),
                    delivery.getLastResponseCode(),
                    delivery.getLastError(),
                    delivery.getLastErrorClass() == null
                            ? null
                            : delivery.getLastErrorClass().name(),
                    delivery.getLeaseUntil(),
                    delivery.getLeaseOwner(),
                    delivery.getStatus().isReplayable(),
                    delivery.getCreatedAt(),
                    delivery.getUpdatedAt());
        }
    }

    public record AttemptResponse(
            UUID id,
            int attemptNumber,
            String kind,
            String outcome,
            Integer responseStatus,
            String responseBodyExcerpt,
            String responseHeadersExcerpt,
            String errorMessage,
            String errorClass,
            Integer retryAfterSeconds,
            Integer requestBytes,
            int durationMs,
            String workerId,
            String traceId,
            Instant startedAt,
            Instant completedAt) {

        public static AttemptResponse from(DeliveryAttempt attempt) {
            return new AttemptResponse(
                    attempt.getId(),
                    attempt.getAttemptNumber(),
                    attempt.getKind().name(),
                    attempt.getOutcome().name(),
                    attempt.getResponseStatus(),
                    attempt.getResponseBodyExcerpt(),
                    attempt.getResponseHeadersExcerpt(),
                    attempt.getErrorMessage(),
                    attempt.getErrorClass() == null ? null : attempt.getErrorClass().name(),
                    attempt.getRetryAfterSeconds(),
                    attempt.getRequestBytes(),
                    attempt.getDurationMs(),
                    attempt.getWorkerId(),
                    attempt.getTraceId(),
                    attempt.getStartedAt(),
                    attempt.getCompletedAt());
        }
    }

    /** The delivery-detail payload: everything the detail screen needs in one call. */
    public record DetailResponse(
            Response delivery,
            JsonNode eventPayload,
            List<AttemptResponse> attempts,
            List<ReplayRecordResponse> replays) {}

    public record ReplayRecordResponse(
            UUID id,
            Instant createdAt,
            String previousStatus,
            int attemptsBefore,
            String reason,
            UUID requestedBy,
            UUID batchId) {}

    public record ReplayRequest(@Size(max = 500) String reason) {}

    public record ReplayResponse(
            UUID deliveryId, boolean accepted, String status, String message) {}

    public record BulkReplayRequest(
            List<UUID> deliveryIds, UUID endpointId, @Size(max = 500) String reason) {}

    public record BulkReplayResponse(
            UUID batchId, int requested, int accepted, int skipped, List<ReplayResponse> results) {}

    /** The dead-letter list row, with the extra context an operator needs to triage. */
    public record DeadLetterResponse(
            UUID id,
            UUID eventId,
            String eventType,
            UUID endpointId,
            String endpointName,
            String endpointUrl,
            int attemptCount,
            int replayCount,
            Integer lastResponseCode,
            String lastError,
            String lastErrorClass,
            Instant firstAttemptAt,
            Instant failedAt,
            Instant createdAt) {}
}

package com.relayforge.events.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayforge.events.Event;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for event ingestion and history. */
public final class EventDtos {

    private EventDtos() {}

    public record PublishRequest(
            /**
             * Dotted, lower-case convention ({@code order.created}).
             *
             * <p>Constrained by a pattern rather than left free-form because the
             * event type is used in wildcard subscription matching, appears as a
             * filter in the UI, and ends up in log lines. Allowing arbitrary text
             * would make {@code order.*} matching ambiguous and let a caller
             * inject newlines into logs.
             */
            @NotBlank
                    @Size(max = 120)
                    @Pattern(
                            regexp = "^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,119}$",
                            message =
                                    "must contain only letters, digits, dots, underscores and"
                                            + " hyphens")
                    String eventType,
            @NotNull JsonNode payload) {}

    /**
     * The ingestion response.
     *
     * <p>Returns the deliveries that were <em>created</em>, not their outcomes:
     * ingestion is deliberately asynchronous, so by design nothing is known about
     * delivery yet. Saying so explicitly — rather than returning a bare 202 —
     * lets a caller record the delivery ids and follow them up.
     *
     * @param deduplicated true when an earlier request with the same idempotency
     *     key produced this event, so nothing new was created
     */
    public record PublishResponse(
            UUID eventId,
            String eventType,
            Instant createdAt,
            boolean deduplicated,
            int deliveriesCreated,
            List<AcceptedDelivery> deliveries) {}

    public record AcceptedDelivery(UUID deliveryId, UUID endpointId, String endpointName, String status) {}

    public record Response(
            UUID id,
            String eventType,
            JsonNode payload,
            String idempotencyKey,
            String source,
            int payloadBytes,
            Instant createdAt,
            DeliverySummary deliverySummary) {

        public static Response from(Event event, DeliverySummary summary) {
            return new Response(
                    event.getId(),
                    event.getEventType(),
                    event.getPayload(),
                    event.getIdempotencyKey(),
                    event.getSource().name(),
                    event.getPayloadBytes(),
                    event.getCreatedAt(),
                    summary);
        }
    }

    /** Counts by status for one event's deliveries, for the list view. */
    public record DeliverySummary(
            int total, int succeeded, int failed, int pending, int cancelled) {

        public static DeliverySummary empty() {
            return new DeliverySummary(0, 0, 0, 0, 0);
        }
    }

    /** List-view projection: omits the payload, which can be large. */
    public record ListItem(
            UUID id,
            String eventType,
            String idempotencyKey,
            String source,
            int payloadBytes,
            Instant createdAt,
            DeliverySummary deliverySummary) {}
}

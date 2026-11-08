package com.relayforge.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayforge.delivery.AttemptKind;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryAttempt;
import com.relayforge.delivery.DeliveryAttemptRepository;
import com.relayforge.delivery.DeliveryExecutor;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.events.dto.EventDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sends a one-off test event to a single endpoint and reports the result
 * synchronously.
 *
 * <h2>Why this runs the attempt inline</h2>
 *
 * Normal ingestion is asynchronous on purpose. A connectivity test is the one
 * case where the user is sitting in front of the screen waiting for an answer,
 * so the attempt is executed on the request thread and the real outcome is
 * returned.
 *
 * <p>It is still a genuine delivery, not a simulation: a real event row, a real
 * delivery row, a real signed request through {@link DeliveryExecutor}, and a
 * real attempt recorded in the history. A test that took a different code path
 * would be able to pass while real deliveries fail — which is the one thing a
 * test must not do.
 *
 * <p>The attempt budget is 1, so a failing test reports the failure immediately
 * instead of leaving a retry schedule running behind the user's back.
 */
@Service
public class EndpointTestService {

    private final EventIngestionService ingestion;
    private final DeliveryExecutor executor;
    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final ObjectMapper objectMapper;

    public EndpointTestService(
            EventIngestionService ingestion,
            DeliveryExecutor executor,
            DeliveryRepository deliveries,
            DeliveryAttemptRepository attempts,
            ObjectMapper objectMapper) {
        this.ingestion = ingestion;
        this.executor = executor;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.objectMapper = objectMapper;
    }

    public EndpointDtos.TestResult sendTestEvent(UUID tenantId, UUID apiKeyId, UUID endpointId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", "This is a RelayForge connectivity test.");
        payload.put("endpointId", endpointId.toString());
        payload.put("sentAt", Instant.now().toString());

        EventDtos.PublishResponse published =
                ingestion.ingest(
                        tenantId,
                        apiKeyId,
                        new EventDtos.PublishRequest("relayforge.endpoint.test", payload),
                        // No idempotency key: each press of "Send test" is a
                        // deliberate new test, not a retry of the previous one.
                        null,
                        Event.Source.ENDPOINT_TEST,
                        endpointId);

        if (published.deliveries().isEmpty()) {
            return new EndpointDtos.TestResult(
                    false,
                    null,
                    null,
                    "No delivery was created. The endpoint may be paused, inactive or deleted.",
                    "NONE",
                    published.eventId(),
                    null);
        }

        UUID deliveryId = published.deliveries().getFirst().deliveryId();

        // Run the attempt now rather than waiting for a worker to pick it up.
        // Budget of one: a test should report what happened, not start retrying.
        deliveries
                .findByIdAndTenantId(deliveryId, tenantId)
                .ifPresent(
                        delivery -> {
                            delivery.setMaxAttempts(1);
                            deliveries.save(delivery);
                        });

        executor.execute(deliveryId, AttemptKind.MANUAL_TEST);

        List<DeliveryAttempt> recorded =
                attempts.findByDeliveryIdAndTenantIdOrderByAttemptNumberAsc(deliveryId, tenantId);
        Delivery delivery = deliveries.findByIdAndTenantId(deliveryId, tenantId).orElse(null);

        if (recorded.isEmpty()) {
            return new EndpointDtos.TestResult(
                    false,
                    null,
                    null,
                    delivery == null
                            ? "The test delivery could not be executed."
                            : "The test delivery was not attempted: " + delivery.getLastError(),
                    "NONE",
                    published.eventId(),
                    deliveryId);
        }

        DeliveryAttempt attempt = recorded.getLast();
        boolean delivered =
                attempt.getOutcome() == com.relayforge.delivery.AttemptOutcome.SUCCESS;
        return new EndpointDtos.TestResult(
                delivered,
                attempt.getResponseStatus(),
                attempt.getDurationMs(),
                delivered ? null : attempt.getErrorMessage(),
                attempt.getErrorClass() == null ? "NONE" : attempt.getErrorClass().name(),
                published.eventId(),
                deliveryId);
    }
}

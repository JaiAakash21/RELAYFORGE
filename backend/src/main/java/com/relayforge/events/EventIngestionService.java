package com.relayforge.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayforge.common.Ids;
import com.relayforge.common.ValidationException;
import com.relayforge.config.RelayForgeProperties;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.DeliveryStatus;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.endpoints.WebhookEndpointRepository;
import com.relayforge.events.dto.EventDtos;
import com.relayforge.messaging.DeliveryRequest;
import com.relayforge.messaging.Topics;
import com.relayforge.observability.PipelineMetrics;
import com.relayforge.outbox.OutboxEvent;
import com.relayforge.outbox.OutboxEventRepository;
import com.relayforge.retry.RetryPolicy;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts an event, fans it out to matching endpoints and schedules its relay —
 * all in one database transaction.
 *
 * <h2>What makes this transaction the heart of the system</h2>
 *
 * Four things are written: the event, one delivery per matching endpoint, one
 * outbox row per delivery, and nothing else. In particular <em>nothing is
 * published to Kafka here</em>. That is the entire point of the transactional
 * outbox: at the moment the HTTP response says "accepted", a single atomic
 * commit has established both that the event exists and that it is queued for
 * relay. There is no window in which one is true and the other is not.
 *
 * <p>Compare the two obvious alternatives:
 *
 * <ul>
 *   <li><b>Commit, then publish to Kafka.</b> A crash between them — or simply a
 *       broker that is briefly unreachable — produces an event the API accepted
 *       and that no worker will ever hear about. Silent, permanent data loss, and
 *       the user has a 202 saying otherwise.
 *   <li><b>Publish, then commit.</b> A commit failure leaves workers chasing a
 *       delivery row that does not exist, and the ingestion request has to decide
 *       whether to report success for an event it did not store.
 * </ul>
 *
 * <p>The cost is latency on the relay — up to one publisher poll interval — and
 * the possibility of a duplicate Kafka message. Both are cheap. Losing an event
 * is not.
 *
 * <h2>Why ingestion does not wait for delivery</h2>
 *
 * The API returns as soon as the commit lands. Waiting for the webhooks would
 * couple the caller's latency to the slowest receiver on the subscription list,
 * and would mean a single unresponsive endpoint could make a tenant's own API
 * appear to be failing.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final PipelineMetrics metrics;
    private final Tracer tracer;
    private final RelayForgeProperties properties;

    public EventIngestionService(
            EventRepository events,
            DeliveryRepository deliveries,
            WebhookEndpointRepository endpoints,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            PipelineMetrics metrics,
            Tracer tracer,
            RelayForgeProperties properties) {
        this.events = events;
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.tracer = tracer;
        this.properties = properties;
    }

    /**
     * Ingests an event.
     *
     * @param idempotencyKey optional caller-supplied key, scoped to the tenant
     * @param targetEndpointId restrict fan-out to one endpoint (endpoint tests
     *     and the Reliability Playground), or {@code null} for normal matching
     */
    @Transactional
    public EventDtos.PublishResponse ingest(
            UUID tenantId,
            UUID apiKeyId,
            EventDtos.PublishRequest request,
            String idempotencyKey,
            Event.Source source,
            UUID targetEndpointId) {

        String normalisedKey = normaliseIdempotencyKey(idempotencyKey);

        // Fast path: an already-seen key short-circuits before any work is done.
        if (normalisedKey != null) {
            Optional<Event> existing =
                    events.findByTenantIdAndIdempotencyKey(tenantId, normalisedKey);
            if (existing.isPresent()) {
                metrics.recordEventDeduplicated();
                return describeExisting(existing.get(), true);
            }
        }

        byte[] payloadBytes = measurePayload(request.payload());
        validatePayload(request, payloadBytes.length, source);

        List<WebhookEndpoint> matching = resolveTargets(tenantId, request.eventType(), targetEndpointId);
        if (matching.size() > properties.ingestion().maxFanout()) {
            metrics.recordEventRejected();
            throw new ValidationException(
                    "fanout_too_large",
                    "This event matches "
                            + matching.size()
                            + " endpoints, which exceeds the configured maximum of "
                            + properties.ingestion().maxFanout());
        }

        Event event =
                new Event(
                        Ids.newId(),
                        tenantId,
                        request.eventType(),
                        request.payload(),
                        normalisedKey,
                        source,
                        apiKeyId,
                        payloadBytes.length);

        try {
            events.save(event);
            // Flush now so a duplicate idempotency key surfaces as a constraint
            // violation here, where it can be translated into the correct
            // deduplicated response, rather than at commit time where the
            // transaction is already unrecoverable.
            events.flush();
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests with the same key: the unique index decided
            // the winner. The loser returns the winner's event, which is exactly
            // what idempotency promises.
            if (normalisedKey != null) {
                Optional<Event> winner =
                        events.findByTenantIdAndIdempotencyKey(tenantId, normalisedKey);
                if (winner.isPresent()) {
                    metrics.recordEventDeduplicated();
                    return describeExisting(winner.get(), true);
                }
            }
            throw e;
        }

        String traceParent = currentTraceParent();
        Instant now = Instant.now();
        List<EventDtos.AcceptedDelivery> accepted = new ArrayList<>(matching.size());

        for (WebhookEndpoint endpoint : matching) {
            RetryPolicy policy = RetryPolicy.forEndpoint(endpoint, properties);

            Delivery delivery =
                    new Delivery(
                            Ids.newId(),
                            tenantId,
                            event.getId(),
                            endpoint.getId(),
                            policy.maxAttempts(),
                            // Marked as queued now because the outbox row written
                            // below is about to enqueue it. Leaving this null would
                            // make the recovery dispatcher treat a healthy new
                            // delivery as a lost message and enqueue it twice.
                            now);
            deliveries.save(delivery);

            outbox.save(buildOutboxRecord(tenantId, event, delivery, endpoint, traceParent));

            accepted.add(
                    new EventDtos.AcceptedDelivery(
                            delivery.getId(),
                            endpoint.getId(),
                            endpoint.getName(),
                            DeliveryStatus.PENDING.name()));
        }

        metrics.recordEventAccepted(accepted.size());
        if (accepted.isEmpty()) {
            // Accepted, but nothing subscribes. Recorded rather than rejected:
            // the event is real history, and a subscription added later can be
            // satisfied by replaying it.
            log.debug(
                    "Event {} of type {} matched no endpoints for tenant {}",
                    event.getId(),
                    event.getEventType(),
                    tenantId);
        }

        return new EventDtos.PublishResponse(
                event.getId(),
                event.getEventType(),
                event.getCreatedAt(),
                false,
                accepted.size(),
                accepted);
    }

    /** Builds the outbox row that will become one Kafka message. */
    private OutboxEvent buildOutboxRecord(
            UUID tenantId,
            Event event,
            Delivery delivery,
            WebhookEndpoint endpoint,
            String traceParent) {

        DeliveryRequest message =
                new DeliveryRequest(
                        delivery.getId(),
                        tenantId,
                        event.getId(),
                        endpoint.getId(),
                        0,
                        System.currentTimeMillis());
        JsonNode payload = objectMapper.valueToTree(message);

        return new OutboxEvent(
                Ids.newId(),
                "delivery",
                delivery.getId(),
                tenantId,
                "delivery.requested",
                Topics.DELIVERY_REQUESTS,
                // Partition by endpoint so one destination's deliveries are
                // handled in order by a single consumer.
                endpoint.getId().toString(),
                payload,
                traceParent);
    }

    /**
     * Selects the endpoints an event should be delivered to.
     *
     * <p>Candidates come from one tenant-scoped query and the wildcard decision
     * is made by {@link WebhookEndpoint#subscribesTo}, so the matching rule has a
     * single definition rather than one in Java and a subtly different one in
     * SQL.
     */
    private List<WebhookEndpoint> resolveTargets(
            UUID tenantId, String eventType, UUID targetEndpointId) {
        if (targetEndpointId != null) {
            // A directed send (endpoint test, playground run) ignores
            // subscriptions by design: the user is testing this endpoint, not the
            // routing. Still tenant-scoped, so it cannot target another tenant's
            // endpoint.
            return endpoints
                    .findActiveByIdAndTenant(targetEndpointId, tenantId)
                    .map(List::of)
                    .orElseGet(List::of);
        }
        return endpoints.findDeliverableByTenant(tenantId).stream()
                .filter(endpoint -> endpoint.subscribesTo(eventType))
                .toList();
    }

    /** Describes an event that already existed, for the deduplicated response. */
    private EventDtos.PublishResponse describeExisting(Event event, boolean deduplicated) {
        List<EventDtos.AcceptedDelivery> existing =
                deliveries.findByEventIdOrderByCreatedAtAsc(event.getId()).stream()
                        .map(
                                delivery ->
                                        new EventDtos.AcceptedDelivery(
                                                delivery.getId(),
                                                delivery.getEndpointId(),
                                                null,
                                                delivery.getStatus().name()))
                        .toList();
        return new EventDtos.PublishResponse(
                event.getId(),
                event.getEventType(),
                event.getCreatedAt(),
                deduplicated,
                existing.size(),
                existing);
    }

    /**
     * Serialises the payload to measure it.
     *
     * <p>Measured after parsing rather than from the raw request length: the
     * recorded size should describe what is stored, and a caller could otherwise
     * pad the request with whitespace to make the figure meaningless.
     */
    private byte[] measurePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new ValidationException("The event payload could not be serialised");
        }
    }

    /**
     * Validates the payload and the event type.
     *
     * @param source who is publishing. The reserved {@code relayforge.} prefix is
     *     reserved <em>from callers</em>, so platform-generated events —
     *     endpoint connectivity tests and Reliability Playground runs — are the
     *     one thing entitled to use it. Enforcing the rule against the platform
     *     itself would be enforcing it against the party doing the reserving.
     */
    private void validatePayload(
            EventDtos.PublishRequest request, int payloadBytes, Event.Source source) {
        if (payloadBytes > properties.ingestion().maxPayloadBytes()) {
            metrics.recordEventRejected();
            throw new ValidationException(
                    "payload_too_large",
                    "The event payload is "
                            + payloadBytes
                            + " bytes, which exceeds the maximum of "
                            + properties.ingestion().maxPayloadBytes()
                            + " bytes");
        }
        if (!request.payload().isObject() && !request.payload().isArray()) {
            metrics.recordEventRejected();
            throw new ValidationException(
                    "payload_not_structured",
                    "The event payload must be a JSON object or array, not a bare scalar");
        }
        if (source != Event.Source.API) {
            return;
        }
        for (String reserved : properties.ingestion().reservedEventTypePrefixes()) {
            if (request.eventType().startsWith(reserved)) {
                metrics.recordEventRejected();
                throw new ValidationException(
                        "reserved_event_type",
                        "Event types beginning with '" + reserved + "' are reserved by the platform");
            }
        }
    }

    private static String normaliseIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > 255) {
            throw new ValidationException(
                    "idempotency_key_too_long",
                    "The Idempotency-Key header must be 255 characters or fewer");
        }
        // Control characters would make the key unsafe to echo back in logs and
        // error messages.
        for (char c : trimmed.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                throw new ValidationException(
                        "idempotency_key_invalid",
                        "The Idempotency-Key header must not contain control characters");
            }
        }
        return trimmed;
    }

    private String currentTraceParent() {
        var span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        var context = span.context();
        if (context.traceId() == null || context.spanId() == null) {
            return null;
        }
        return "00-"
                + context.traceId()
                + "-"
                + context.spanId()
                + "-"
                + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    static {
        assert StandardCharsets.UTF_8 != null;
    }
}

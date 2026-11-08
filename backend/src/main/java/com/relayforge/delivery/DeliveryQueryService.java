package com.relayforge.delivery;

import com.relayforge.common.NotFoundException;
import com.relayforge.deadletter.ReplayRecordRepository;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.endpoints.WebhookEndpointRepository;
import com.relayforge.events.Event;
import com.relayforge.events.EventRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read paths for deliveries, attempts and the dead-letter queue.
 *
 * <p>Separate from the write/execution path because they have genuinely
 * different shapes: these methods are read-only, batch their lookups, and exist
 * to serve screens, while {@link DeliveryExecutor} is a single-row state machine
 * driven by a worker. Keeping them apart stops query convenience methods from
 * creeping into the concurrency-critical code.
 */
@Service
public class DeliveryQueryService {

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final WebhookEndpointRepository endpoints;
    private final EventRepository events;
    private final ReplayRecordRepository replays;

    public DeliveryQueryService(
            DeliveryRepository deliveries,
            DeliveryAttemptRepository attempts,
            WebhookEndpointRepository endpoints,
            EventRepository events,
            ReplayRecordRepository replays) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.endpoints = endpoints;
        this.events = events;
        this.replays = replays;
    }

    @Transactional(readOnly = true)
    public Page<DeliveryDtos.Response> search(
            UUID tenantId,
            DeliveryStatus status,
            UUID endpointId,
            UUID eventId,
            Instant from,
            Instant to,
            Pageable pageable) {

        // Sorting is applied here rather than inside the predicate: a
        // Specification describes WHAT to select, and the caller's Pageable
        // describes how to order and slice it. Newest-first with a tiebreak on
        // id keeps pagination stable when several rows share a timestamp —
        // without it, a row can appear on two consecutive pages or on neither.
        Page<Delivery> page =
                deliveries.findAll(
                        DeliverySpecifications.search(
                                tenantId, status, endpointId, eventId, from, to),
                        withNewestFirst(pageable));

        // Two batch lookups instead of two per row. With 50 rows the naive
        // version is 100 extra queries; this is the classic N+1 and it is the
        // single easiest way to make a list endpoint slow.
        Map<UUID, WebhookEndpoint> endpointsById = loadEndpoints(page.getContent());
        Map<UUID, String> eventTypes = loadEventTypes(page.getContent());

        return page.map(
                delivery -> {
                    WebhookEndpoint endpoint = endpointsById.get(delivery.getEndpointId());
                    return DeliveryDtos.Response.from(
                            delivery,
                            endpoint == null ? null : endpoint.getName(),
                            endpoint == null ? null : endpoint.getUrl(),
                            eventTypes.get(delivery.getEventId()));
                });
    }

    @Transactional(readOnly = true)
    public DeliveryDtos.DetailResponse detail(UUID tenantId, UUID deliveryId) {
        Delivery delivery =
                deliveries
                        .findByIdAndTenantId(deliveryId, tenantId)
                        .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElse(null);
        Event event = events.findById(delivery.getEventId()).orElse(null);

        List<DeliveryDtos.AttemptResponse> attemptResponses =
                attempts
                        .findByDeliveryIdAndTenantIdOrderByAttemptNumberAsc(deliveryId, tenantId)
                        .stream()
                        .map(DeliveryDtos.AttemptResponse::from)
                        .toList();

        List<DeliveryDtos.ReplayRecordResponse> replayResponses =
                replays.findByDeliveryIdOrderByCreatedAtDesc(deliveryId).stream()
                        .map(
                                record ->
                                        new DeliveryDtos.ReplayRecordResponse(
                                                record.getId(),
                                                record.getCreatedAt(),
                                                record.getPreviousStatus(),
                                                record.getAttemptsBefore(),
                                                record.getReason(),
                                                record.getRequestedBy(),
                                                record.getBatchId()))
                        .toList();

        return new DeliveryDtos.DetailResponse(
                DeliveryDtos.Response.from(
                        delivery,
                        endpoint == null ? null : endpoint.getName(),
                        endpoint == null ? null : endpoint.getUrl(),
                        event == null ? null : event.getEventType()),
                event == null ? null : event.getPayload(),
                attemptResponses,
                replayResponses);
    }

    @Transactional(readOnly = true)
    public List<DeliveryDtos.AttemptResponse> attempts(UUID tenantId, UUID deliveryId) {
        // Confirms tenant ownership first, so the attempt list cannot be used to
        // read another tenant's history by delivery id.
        deliveries
                .findByIdAndTenantId(deliveryId, tenantId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        return attempts
                .findByDeliveryIdAndTenantIdOrderByAttemptNumberAsc(deliveryId, tenantId)
                .stream()
                .map(DeliveryDtos.AttemptResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<DeliveryDtos.DeadLetterResponse> deadLetters(
            UUID tenantId, UUID endpointId, Pageable pageable) {

        Page<Delivery> page =
                deliveries.findAll(
                        DeliverySpecifications.deadLetters(tenantId, endpointId),
                        withRecentlyFailedFirst(pageable));
        Map<UUID, WebhookEndpoint> endpointsById = loadEndpoints(page.getContent());
        Map<UUID, String> eventTypes = loadEventTypes(page.getContent());

        return page.map(
                delivery -> {
                    WebhookEndpoint endpoint = endpointsById.get(delivery.getEndpointId());
                    return new DeliveryDtos.DeadLetterResponse(
                            delivery.getId(),
                            delivery.getEventId(),
                            eventTypes.get(delivery.getEventId()),
                            delivery.getEndpointId(),
                            endpoint == null ? null : endpoint.getName(),
                            endpoint == null ? null : endpoint.getUrl(),
                            delivery.getAttemptCount(),
                            delivery.getReplayCount(),
                            delivery.getLastResponseCode(),
                            delivery.getLastError(),
                            delivery.getLastErrorClass() == null
                                    ? null
                                    : delivery.getLastErrorClass().name(),
                            delivery.getFirstAttemptAt(),
                            delivery.getCompletedAt() != null
                                    ? delivery.getCompletedAt()
                                    : delivery.getUpdatedAt(),
                            delivery.getCreatedAt());
                });
    }

    /**
     * Per-event delivery counts for the event list.
     *
     * <p>One grouped query for the whole page. Counting per row would be an N+1
     * that gets slower exactly as the page gets more interesting.
     */
    @Transactional(readOnly = true)
    public Map<UUID, com.relayforge.events.dto.EventDtos.DeliverySummary> summariseByEvent(
            List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, int[]> counters = new HashMap<>();
        for (UUID eventId : eventIds) {
            counters.put(eventId, new int[5]);
        }
        for (Object[] row : deliveries.countByEventAndStatus(eventIds)) {
            UUID eventId = (UUID) row[0];
            DeliveryStatus status = (DeliveryStatus) row[1];
            int count = ((Number) row[2]).intValue();
            int[] slots = counters.get(eventId);
            if (slots == null) {
                continue;
            }
            slots[0] += count;
            switch (status) {
                case SUCCESS -> slots[1] += count;
                case FAILED -> slots[2] += count;
                case PENDING, IN_PROGRESS, RETRY_SCHEDULED -> slots[3] += count;
                case CANCELLED -> slots[4] += count;
            }
        }
        return counters.entrySet().stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> {
                                    int[] s = entry.getValue();
                                    return new com.relayforge.events.dto.EventDtos
                                            .DeliverySummary(s[0], s[1], s[2], s[3], s[4]);
                                }));
    }

    /** Newest first, with the primary key as a stable tiebreak. */
    private static Pageable withNewestFirst(Pageable pageable) {
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    /** Most recently failed first, for the dead-letter queue. */
    private static Pageable withRecentlyFailedFirst(Pageable pageable) {
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
    }

    private Map<UUID, WebhookEndpoint> loadEndpoints(List<Delivery> page) {
        Set<UUID> ids =
                page.stream().map(Delivery::getEndpointId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return endpoints.findAllById(ids).stream()
                .collect(Collectors.toMap(WebhookEndpoint::getId, endpoint -> endpoint));
    }

    private Map<UUID, String> loadEventTypes(List<Delivery> page) {
        Set<UUID> ids = page.stream().map(Delivery::getEventId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return events.findAllById(ids).stream()
                .collect(Collectors.toMap(Event::getId, Event::getEventType));
    }
}

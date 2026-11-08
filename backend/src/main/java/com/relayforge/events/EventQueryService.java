package com.relayforge.events;

import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.endpoints.WebhookEndpointRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read paths for events. */
@Service
public class EventQueryService {

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;

    public EventQueryService(
            EventRepository events,
            DeliveryRepository deliveries,
            WebhookEndpointRepository endpoints) {
        this.events = events;
        this.deliveries = deliveries;
        this.endpoints = endpoints;
    }

    @Transactional(readOnly = true)
    public Page<Event> search(
            UUID tenantId,
            String eventType,
            String search,
            Instant from,
            Instant to,
            Pageable pageable) {
        return events.findAll(
                EventSpecifications.search(tenantId, eventType, search, from, to),
                PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        // Stable ordering: the id tiebreak stops a row from
                        // appearing on two pages when timestamps collide.
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    @Transactional(readOnly = true)
    public Optional<Event> find(UUID tenantId, UUID eventId) {
        return events.findByIdAndTenantId(eventId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<String> distinctEventTypes(UUID tenantId) {
        return events.findDistinctEventTypes(tenantId);
    }

    @Transactional(readOnly = true)
    public List<DeliveryDtos.Response> deliveriesForEvent(UUID tenantId, UUID eventId) {
        List<Delivery> rows =
                deliveries.findByEventIdAndTenantIdOrderByCreatedAtAsc(eventId, tenantId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, WebhookEndpoint> endpointsById =
                endpoints
                        .findAllById(
                                rows.stream()
                                        .map(Delivery::getEndpointId)
                                        .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(WebhookEndpoint::getId, e -> e));
        String eventType =
                events.findByIdAndTenantId(eventId, tenantId)
                        .map(Event::getEventType)
                        .orElse(null);

        return rows.stream()
                .map(
                        delivery -> {
                            WebhookEndpoint endpoint = endpointsById.get(delivery.getEndpointId());
                            return DeliveryDtos.Response.from(
                                    delivery,
                                    endpoint == null ? null : endpoint.getName(),
                                    endpoint == null ? null : endpoint.getUrl(),
                                    eventType);
                        })
                .toList();
    }
}

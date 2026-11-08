package com.relayforge.events;

import com.relayforge.common.NotFoundException;
import com.relayforge.common.PageResponse;
import com.relayforge.delivery.DeliveryQueryService;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.events.dto.EventDtos;
import com.relayforge.ratelimit.RateLimiter;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Event ingestion and history. */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Publish events and inspect their delivery history")
public class EventController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventIngestionService ingestion;
    private final EventQueryService query;
    private final DeliveryQueryService deliveryQuery;
    private final RateLimiter rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public EventController(
            EventIngestionService ingestion,
            EventQueryService query,
            DeliveryQueryService deliveryQuery,
            RateLimiter rateLimiter,
            CurrentPrincipal currentPrincipal) {
        this.ingestion = ingestion;
        this.query = query;
        this.deliveryQuery = deliveryQuery;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    @Operation(
            summary = "Publish an event",
            description =
                    "Persists the event, fans it out to every subscribed endpoint and queues the"
                            + " deliveries — all in one database transaction. Returns as soon as"
                            + " that transaction commits; it does not wait for the webhooks to be"
                            + " delivered. Supply an Idempotency-Key header to make retries of"
                            + " this call safe.")
    public ResponseEntity<EventDtos.PublishResponse> publish(
            @Valid @RequestBody EventDtos.PublishRequest request,
            @Parameter(
                            description =
                                    "Tenant-scoped key. A repeated key returns the original event"
                                            + " instead of creating a second one.")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        RelayForgePrincipal principal = currentPrincipal.require();
        rateLimiter.checkEventIngestion(principal.tenantId(), principal.apiKeyId());

        EventDtos.PublishResponse response =
                ingestion.ingest(
                        principal.tenantId(),
                        principal.apiKeyId(),
                        request,
                        idempotencyKey,
                        Event.Source.API,
                        null);

        // 202 Accepted, not 201 Created: the event is stored, but the thing the
        // caller actually cares about — delivery — has not happened yet. 202 is
        // the honest status for "recorded, will be acted on".
        HttpStatus status = response.deduplicated() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    @Operation(summary = "List events with per-event delivery counts")
    public PageResponse<EventDtos.ListItem> list(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        UUID tenantId = currentPrincipal.tenantId();
        Page<Event> events =
                query.search(
                        tenantId,
                        blankToNull(eventType),
                        blankToNull(search),
                        from,
                        to,
                        PageRequest.of(Math.max(0, page), clampSize(size)));

        Map<UUID, EventDtos.DeliverySummary> summaries =
                deliveryQuery.summariseByEvent(events.getContent().stream().map(Event::getId).toList());

        return PageResponse.from(
                events,
                event ->
                        new EventDtos.ListItem(
                                event.getId(),
                                event.getEventType(),
                                event.getIdempotencyKey(),
                                event.getSource().name(),
                                event.getPayloadBytes(),
                                event.getCreatedAt(),
                                summaries.getOrDefault(
                                        event.getId(), EventDtos.DeliverySummary.empty())));
    }

    @GetMapping("/types")
    @Operation(summary = "List the distinct event types this tenant has published")
    public List<String> eventTypes() {
        return query.distinctEventTypes(currentPrincipal.tenantId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one event, including its payload")
    public EventDtos.Response get(@PathVariable UUID id) {
        UUID tenantId = currentPrincipal.tenantId();
        Event event =
                query.find(tenantId, id).orElseThrow(() -> new NotFoundException("Event", id));
        EventDtos.DeliverySummary summary =
                deliveryQuery
                        .summariseByEvent(List.of(id))
                        .getOrDefault(id, EventDtos.DeliverySummary.empty());
        return EventDtos.Response.from(event, summary);
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "List the deliveries created for one event")
    public List<DeliveryDtos.Response> deliveries(@PathVariable UUID id) {
        return query.deliveriesForEvent(currentPrincipal.tenantId(), id);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

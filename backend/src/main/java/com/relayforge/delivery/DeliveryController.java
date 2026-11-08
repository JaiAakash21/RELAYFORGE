package com.relayforge.delivery;

import com.relayforge.common.PageResponse;
import com.relayforge.deadletter.ReplayService;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import com.relayforge.tenancy.TenantRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Delivery inspection and replay. */
@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries", description = "Delivery state, attempt history and replay")
public class DeliveryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryQueryService query;
    private final ReplayService replayService;
    private final CurrentPrincipal currentPrincipal;

    public DeliveryController(
            DeliveryQueryService query,
            ReplayService replayService,
            CurrentPrincipal currentPrincipal) {
        this.query = query;
        this.replayService = replayService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "List deliveries, newest first")
    public PageResponse<DeliveryDtos.Response> list(
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var result =
                query.search(
                        currentPrincipal.tenantId(),
                        status,
                        endpointId,
                        eventId,
                        from,
                        to,
                        PageRequest.of(Math.max(0, page), clampSize(size)));
        return PageResponse.of(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Fetch a delivery with its full attempt history",
            description =
                    "Returns the delivery, the event payload, every recorded attempt and every"
                            + " replay — everything the delivery detail screen needs in one call.")
    public DeliveryDtos.DetailResponse get(@PathVariable UUID id) {
        return query.detail(currentPrincipal.tenantId(), id);
    }

    @GetMapping("/{id}/attempts")
    @Operation(summary = "List the recorded attempts for one delivery")
    public List<DeliveryDtos.AttemptResponse> attempts(@PathVariable UUID id) {
        return query.attempts(currentPrincipal.tenantId(), id);
    }

    @PostMapping("/{id}/replay")
    @Operation(
            summary = "Replay a failed delivery",
            description =
                    "Reopens the delivery with a fresh attempt budget. Previous attempts are kept:"
                            + " attempt numbering continues upward rather than restarting, so the"
                            + " record of why it failed is never erased.")
    public DeliveryDtos.ReplayResponse replay(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid DeliveryDtos.ReplayRequest request) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        return replayService.replay(
                principal.tenantId(),
                id,
                principal.userId(),
                request == null ? null : request.reason());
    }

    @PostMapping("/replay")
    @Operation(
            summary = "Replay several deliveries",
            description =
                    "Individual ineligible deliveries are reported as skipped rather than failing"
                            + " the whole batch.")
    public DeliveryDtos.BulkReplayResponse replayBatch(
            @Valid @RequestBody DeliveryDtos.BulkReplayRequest request) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        if (request.endpointId() != null
                && (request.deliveryIds() == null || request.deliveryIds().isEmpty())) {
            return replayService.replayAllForEndpoint(
                    principal.tenantId(),
                    request.endpointId(),
                    principal.userId(),
                    request.reason());
        }
        return replayService.replayBatch(
                principal.tenantId(),
                request.deliveryIds(),
                principal.userId(),
                request.reason());
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }
}

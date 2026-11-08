package com.relayforge.deadletter;

import com.relayforge.common.PageResponse;
import com.relayforge.delivery.DeliveryQueryService;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import com.relayforge.tenancy.TenantRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/**
 * The dead-letter queue.
 *
 * <p>A view over {@code deliveries WHERE status = 'FAILED'}, not a separate
 * store. One row means one delivery, so a replay cannot leave a "dead letter"
 * and a "live delivery" describing the same thing and disagreeing about it.
 */
@RestController
@RequestMapping("/api/v1/dead-letters")
@Tag(
        name = "Dead-letter queue",
        description = "Deliveries that exhausted their attempts, and the controls to replay them")
public class DeadLetterController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryQueryService query;
    private final ReplayService replayService;
    private final CurrentPrincipal currentPrincipal;

    public DeadLetterController(
            DeliveryQueryService query,
            ReplayService replayService,
            CurrentPrincipal currentPrincipal) {
        this.query = query;
        this.replayService = replayService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "List dead-lettered deliveries, most recently failed first")
    public PageResponse<DeliveryDtos.DeadLetterResponse> list(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        var result =
                query.deadLetters(
                        currentPrincipal.tenantId(),
                        endpointId,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));
        return PageResponse.of(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    @PostMapping("/{id}/replay")
    @Operation(summary = "Replay one dead-lettered delivery")
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

    @PostMapping("/replay-all")
    @Operation(
            summary = "Replay every dead letter for one endpoint",
            description = "Bounded to 500 deliveries per call so one request cannot enqueue unbounded work.")
    public DeliveryDtos.BulkReplayResponse replayAll(
            @Valid @RequestBody DeliveryDtos.BulkReplayRequest request) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        if (request.endpointId() == null) {
            throw new com.relayforge.common.ValidationException(
                    "An endpointId is required to replay all dead letters for an endpoint");
        }
        return replayService.replayAllForEndpoint(
                principal.tenantId(), request.endpointId(), principal.userId(), request.reason());
    }

    @GetMapping("/replay-history")
    @Operation(summary = "List this tenant's replay history")
    public List<DeliveryDtos.ReplayRecordResponse> replayHistory() {
        return replayService.history(currentPrincipal.tenantId());
    }
}

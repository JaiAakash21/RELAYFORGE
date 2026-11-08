package com.relayforge.endpoints;

import com.relayforge.common.PageResponse;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.events.EndpointTestService;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import com.relayforge.tenancy.TenantRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint management. */
@RestController
@RequestMapping("/api/v1/endpoints")
@Tag(name = "Endpoints", description = "Webhook destinations, their subscriptions and secrets")
public class EndpointController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EndpointService service;
    private final EndpointTestService testService;
    private final CurrentPrincipal currentPrincipal;

    public EndpointController(
            EndpointService service,
            EndpointTestService testService,
            CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.testService = testService;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    @Operation(
            summary = "Register a webhook endpoint",
            description =
                    "The signing secret is returned exactly once in this response. Destination"
                            + " URLs are validated against the SSRF policy.")
    public ResponseEntity<EndpointDtos.SecretResponse> create(
            @Valid @RequestBody EndpointDtos.CreateRequest request) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        EndpointService.Created created = service.create(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new EndpointDtos.SecretResponse(
                                created.endpoint().getId(),
                                created.plaintextSecret(),
                                "Store this signing secret now. It is encrypted at rest and cannot"
                                        + " be shown again; use rotate-secret to issue a new one."));
    }

    @GetMapping
    @Operation(summary = "List endpoints with 24-hour delivery health")
    public PageResponse<EndpointDtos.Response> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID tenantId = currentPrincipal.tenantId();
        Map<UUID, EndpointDtos.HealthSummary> health = service.healthByEndpoint(tenantId);
        return PageResponse.from(
                service.list(tenantId, PageRequest.of(Math.max(0, page), clampSize(size))),
                endpoint ->
                        EndpointDtos.Response.from(
                                endpoint,
                                health.getOrDefault(
                                        endpoint.getId(), EndpointDtos.HealthSummary.empty())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one endpoint")
    public EndpointDtos.Response get(@PathVariable UUID id) {
        UUID tenantId = currentPrincipal.tenantId();
        WebhookEndpoint endpoint = service.get(tenantId, id);
        Map<UUID, EndpointDtos.HealthSummary> health = service.healthByEndpoint(tenantId);
        return EndpointDtos.Response.from(
                endpoint,
                health.getOrDefault(endpoint.getId(), EndpointDtos.HealthSummary.empty()));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Update an endpoint",
            description = "Only the fields present in the body are changed.")
    public EndpointDtos.Response update(
            @PathVariable UUID id, @Valid @RequestBody EndpointDtos.UpdateRequest request) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        WebhookEndpoint endpoint = service.update(principal.tenantId(), id, request);
        return EndpointDtos.Response.from(endpoint, EndpointDtos.HealthSummary.empty());
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete an endpoint",
            description =
                    "Soft delete: the delivery history is preserved, and any queued deliveries for"
                            + " this endpoint are cancelled.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.ADMIN);
        service.delete(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    @Operation(
            summary = "Pause delivery",
            description =
                    "Queued deliveries are held and retried after resuming — nothing is discarded.")
    public EndpointDtos.Response pause(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        return EndpointDtos.Response.from(
                service.pause(principal.tenantId(), id), EndpointDtos.HealthSummary.empty());
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume delivery")
    public EndpointDtos.Response resume(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        return EndpointDtos.Response.from(
                service.resume(principal.tenantId(), id), EndpointDtos.HealthSummary.empty());
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(
            summary = "Rotate the signing secret",
            description =
                    "The previous secret stays valid for the configured grace window, and both"
                            + " signatures are sent during it, so a receiver can roll over without"
                            + " dropping events.")
    public EndpointDtos.SecretResponse rotateSecret(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.ADMIN);
        String secret = service.rotateSecret(principal.tenantId(), id, null);
        return new EndpointDtos.SecretResponse(
                id,
                secret,
                "Both the new and the previous secret are accepted during the rotation grace"
                        + " window. Revoke the previous secret once your receiver is updated.");
    }

    @PostMapping("/{id}/revoke-previous-secret")
    @Operation(
            summary = "End the rotation window immediately",
            description = "Stops signing with the superseded secret. Use after a leak.")
    public ResponseEntity<Void> revokePreviousSecret(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.ADMIN);
        service.revokePreviousSecret(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(
            summary = "Send a test event to this endpoint",
            description =
                    "Publishes a real, signed event through the full pipeline and returns the"
                            + " delivery it created, so the result reflects actual behaviour rather"
                            + " than a simulated check.")
    public EndpointDtos.TestResult test(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        return testService.sendTestEvent(principal.tenantId(), principal.apiKeyId(), id);
    }

    private static int clampSize(int size) {
        // Clamped rather than rejected: an over-large page size is almost always
        // a client being optimistic, not an attack, and silently capping it is
        // friendlier than a 400. The cap is what protects the database.
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }
}

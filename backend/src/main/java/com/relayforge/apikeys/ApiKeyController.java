package com.relayforge.apikeys;

import com.relayforge.apikeys.dto.ApiKeyDtos;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import com.relayforge.tenancy.TenantRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API key management. Requires a signed-in user, never an API key. */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API keys", description = "Programmatic credentials for event ingestion")
public class ApiKeyController {

    private final ApiKeyService service;
    private final CurrentPrincipal currentPrincipal;

    public ApiKeyController(ApiKeyService service, CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.currentPrincipal = currentPrincipal;
    }

    @PostMapping
    @Operation(
            summary = "Create an API key",
            description =
                    "The full key is returned exactly once, in this response. It is stored only as"
                            + " a hash and cannot be retrieved again.")
    public ResponseEntity<ApiKeyDtos.CreatedResponse> create(
            @Valid @RequestBody ApiKeyDtos.CreateRequest request) {
        // An API key must not be able to mint another API key: that would turn a
        // single leaked ingestion credential into permanent tenant access.
        RelayForgePrincipal principal = currentPrincipal.requireUserSession();
        currentPrincipal.requireRole(TenantRole.ADMIN);

        ApiKeyService.CreatedKey created =
                service.create(
                        principal.tenantId(),
                        principal.userId(),
                        request.name(),
                        request.expiresAt());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new ApiKeyDtos.CreatedResponse(
                                ApiKeyDtos.Response.from(created.record()),
                                created.plaintext(),
                                "Copy this key now. It is stored only as a hash and cannot be"
                                        + " shown again."));
    }

    @GetMapping
    @Operation(
            summary = "List API keys",
            description = "Returns metadata only. Key material is never included.")
    public List<ApiKeyDtos.Response> list() {
        RelayForgePrincipal principal = currentPrincipal.requireUserSession();
        return service.list(principal.tenantId()).stream().map(ApiKeyDtos.Response::from).toList();
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Revoke an API key",
            description =
                    "Takes effect immediately: every API-key request is validated against the"
                            + " database, so a revoked key stops working on the next call.")
    public ApiKeyDtos.Response revoke(@PathVariable UUID id) {
        RelayForgePrincipal principal = currentPrincipal.requireUserSession();
        currentPrincipal.requireRole(TenantRole.ADMIN);
        return ApiKeyDtos.Response.from(service.revoke(principal.tenantId(), id));
    }
}

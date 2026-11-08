package com.relayforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayforge.apikeys.ApiKeyAuthenticator;
import com.relayforge.apikeys.ApiKeyService;
import com.relayforge.auth.AuthService;
import com.relayforge.auth.dto.AuthDtos;
import com.relayforge.common.NotFoundException;
import com.relayforge.deadletter.ReplayService;
import com.relayforge.delivery.DeliveryQueryService;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.endpoints.EndpointService;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.events.Event;
import com.relayforge.events.EventIngestionService;
import com.relayforge.events.EventQueryService;
import com.relayforge.events.dto.EventDtos;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Tenant isolation, enforced at the service and repository layers.
 *
 * <p>Deliberately bypasses the HTTP layer and calls the services directly with a
 * foreign tenant id. A test that went through the API would mostly be proving
 * that the controller passes the right tenant — useful, but it would not catch
 * a service method that ignores the tenant it was given. These call the
 * services the way a future bug would: with the wrong tenant.
 *
 * <p>Every cross-tenant read must be indistinguishable from "does not exist".
 * Returning 403 for a resource that belongs to someone else turns the API into
 * an existence oracle for other tenants' identifiers.
 */
class TenantIsolationIT extends IntegrationTestBase {

    /**
     * No workers in this context.
     *
     * <p>These tests insert delivery rows and assert on their exact state, so a
     * background dispatcher claiming and attempting those rows would make the
     * assertions race. Running as {@code api} means the @WorkerRole beans are
     * never created.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void roleProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("relayforge.role", () -> "api");
    }

    @Autowired private AuthService authService;
    @Autowired private ApiKeyService apiKeyService;
    @Autowired private ApiKeyAuthenticator apiKeyAuthenticator;
    @Autowired private EndpointService endpointService;
    @Autowired private EventIngestionService ingestion;
    @Autowired private EventQueryService eventQuery;
    @Autowired private DeliveryQueryService deliveryQuery;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private ReplayService replayService;
    @Autowired private ObjectMapper objectMapper;

    private record Tenant(UUID tenantId, UUID userId) {}

    private Tenant alice;
    private Tenant bob;

    private UUID aliceEndpointId;
    private UUID aliceEventId;
    private UUID aliceDeliveryId;

    @BeforeEach
    void setUp() {
        resetDatabase();
        alice = register("alice");
        bob = register("bob");

        aliceEndpointId =
                endpointService
                        .create(
                                alice.tenantId(),
                                new EndpointDtos.CreateRequest(
                                        "alice-endpoint",
                                        "https://alice.example.com/hook",
                                        null,
                                        Set.of("order.*"),
                                        null, null, null, null, null, null, null))
                        .endpoint()
                        .getId();

        EventDtos.PublishResponse published =
                ingestion.ingest(
                        alice.tenantId(),
                        null,
                        new EventDtos.PublishRequest(
                                "order.created", objectMapper.createObjectNode().put("secret", 1)),
                        null,
                        Event.Source.API,
                        null);
        aliceEventId = published.eventId();
        aliceDeliveryId = published.deliveries().getFirst().deliveryId();
    }

    private Tenant register(String name) {
        AuthDtos.TokenResponse response =
                authService.register(
                        new AuthDtos.RegisterRequest(
                                name + "-" + UUID.randomUUID() + "@test.dev",
                                "an-adequately-long-password",
                                name,
                                name + " Org"),
                        "isolation-test");
        return new Tenant(response.tenant().id(), response.user().id());
    }

    @Test
    @DisplayName("Bob cannot read Alice's endpoint")
    void cannotReadForeignEndpoint() {
        assertThatThrownBy(() -> endpointService.get(bob.tenantId(), aliceEndpointId))
                .isInstanceOf(NotFoundException.class);
        // And Alice still can.
        assertThat(endpointService.get(alice.tenantId(), aliceEndpointId)).isNotNull();
    }

    @Test
    @DisplayName("Bob cannot read Alice's event")
    void cannotReadForeignEvent() {
        assertThat(eventQuery.find(bob.tenantId(), aliceEventId)).isEmpty();
        assertThat(eventQuery.find(alice.tenantId(), aliceEventId)).isPresent();
    }

    @Test
    @DisplayName("Bob cannot read Alice's delivery or its attempt history")
    void cannotReadForeignDelivery() {
        assertThatThrownBy(() -> deliveryQuery.detail(bob.tenantId(), aliceDeliveryId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> deliveryQuery.attempts(bob.tenantId(), aliceDeliveryId))
                .isInstanceOf(NotFoundException.class);
        assertThat(deliveryQuery.detail(alice.tenantId(), aliceDeliveryId)).isNotNull();
    }

    @Test
    @DisplayName("Bob cannot replay Alice's delivery")
    void cannotReplayForeignDelivery() {
        // Mark it failed so it would be replayable for its owner.
        jdbc.update("UPDATE deliveries SET status = 'FAILED' WHERE id = ?", aliceDeliveryId);

        assertThatThrownBy(
                        () ->
                                replayService.replay(
                                        bob.tenantId(), aliceDeliveryId, bob.userId(), "mine now"))
                .isInstanceOf(NotFoundException.class);

        // Unchanged: no replay was recorded and the status is untouched.
        assertThat(deliveries.findById(aliceDeliveryId).orElseThrow().getReplayCount()).isZero();
    }

    @Test
    @DisplayName("Bob cannot delete, pause or rotate the secret of Alice's endpoint")
    void cannotMutateForeignEndpoint() {
        assertThatThrownBy(() -> endpointService.delete(bob.tenantId(), aliceEndpointId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> endpointService.pause(bob.tenantId(), aliceEndpointId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> endpointService.rotateSecret(bob.tenantId(), aliceEndpointId, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(
                        () ->
                                endpointService.update(
                                        bob.tenantId(),
                                        aliceEndpointId,
                                        new EndpointDtos.UpdateRequest(
                                                "hijacked",
                                                "https://attacker.example.com/",
                                                null, null, null, null, null, null, null, null,
                                                null)))
                .isInstanceOf(NotFoundException.class);

        // Still Alice's, still pointing where she put it.
        assertThat(endpointService.get(alice.tenantId(), aliceEndpointId).getUrl())
                .isEqualTo("https://alice.example.com/hook");
    }

    @Test
    @DisplayName("Bob cannot revoke Alice's API key")
    void cannotRevokeForeignApiKey() {
        ApiKeyService.CreatedKey aliceKey =
                apiKeyService.create(alice.tenantId(), alice.userId(), "alice key", null);

        assertThatThrownBy(
                        () -> apiKeyService.revoke(bob.tenantId(), aliceKey.record().getId()))
                .isInstanceOf(NotFoundException.class);

        // Still usable.
        ApiKeyAuthenticator.Result result =
                apiKeyAuthenticator.authenticate(aliceKey.plaintext());
        assertThat(result).isInstanceOf(ApiKeyAuthenticator.Result.Authenticated.class);
    }

    @Test
    @DisplayName("Bob's API key resolves to Bob's tenant, never Alice's")
    void apiKeyResolvesToOwningTenant() {
        ApiKeyService.CreatedKey bobKey =
                apiKeyService.create(bob.tenantId(), bob.userId(), "bob key", null);

        ApiKeyAuthenticator.Result result = apiKeyAuthenticator.authenticate(bobKey.plaintext());
        assertThat(result).isInstanceOf(ApiKeyAuthenticator.Result.Authenticated.class);
        var authenticated = (ApiKeyAuthenticator.Result.Authenticated) result;
        assertThat(authenticated.principal().tenantId()).isEqualTo(bob.tenantId());
        assertThat(authenticated.principal().tenantId()).isNotEqualTo(alice.tenantId());
    }

    @Test
    @DisplayName("a revoked key stops working on its very next use")
    void revokedKeyFailsImmediately() {
        ApiKeyService.CreatedKey key =
                apiKeyService.create(bob.tenantId(), bob.userId(), "temp", null);
        assertThat(apiKeyAuthenticator.authenticate(key.plaintext()))
                .isInstanceOf(ApiKeyAuthenticator.Result.Authenticated.class);

        apiKeyService.revoke(bob.tenantId(), key.record().getId());

        // No cache, no grace period: every API-key request checks the database,
        // which is the whole reason revocation is immediate.
        ApiKeyAuthenticator.Result after = apiKeyAuthenticator.authenticate(key.plaintext());
        assertThat(after).isInstanceOf(ApiKeyAuthenticator.Result.Rejected.class);
        assertThat(((ApiKeyAuthenticator.Result.Rejected) after).rejection())
                .isEqualTo(ApiKeyAuthenticator.Rejection.REVOKED);
    }

    @Test
    @DisplayName("Bob's listings never contain Alice's rows")
    void listingsAreScoped() {
        assertThat(eventQuery.search(bob.tenantId(), null, null, null, null, PageRequest.of(0, 50)))
                .isEmpty();
        assertThat(
                        deliveryQuery.search(
                                bob.tenantId(), null, null, null, null, null, PageRequest.of(0, 50)))
                .isEmpty();
        assertThat(endpointService.listAll(bob.tenantId())).isEmpty();
        assertThat(apiKeyService.list(bob.tenantId())).isEmpty();

        // Alice sees her own.
        assertThat(
                        eventQuery.search(
                                alice.tenantId(), null, null, null, null, PageRequest.of(0, 50)))
                .hasSize(1);
        assertThat(endpointService.listAll(alice.tenantId())).hasSize(1);
    }

    @Test
    @DisplayName("an idempotency key is scoped to its tenant")
    void idempotencyIsTenantScoped() {
        // Two tenants may legitimately use the same key; one must not
        // deduplicate against the other's event.
        EventDtos.PublishResponse aliceEvent =
                ingestion.ingest(
                        alice.tenantId(),
                        null,
                        new EventDtos.PublishRequest("order.created", objectMapper.createObjectNode()),
                        "shared-key",
                        Event.Source.API,
                        null);
        EventDtos.PublishResponse bobEvent =
                ingestion.ingest(
                        bob.tenantId(),
                        null,
                        new EventDtos.PublishRequest("order.created", objectMapper.createObjectNode()),
                        "shared-key",
                        Event.Source.API,
                        null);

        assertThat(aliceEvent.deduplicated()).isFalse();
        assertThat(bobEvent.deduplicated()).isFalse();
        assertThat(bobEvent.eventId()).isNotEqualTo(aliceEvent.eventId());
    }

    @Test
    @DisplayName("Bob's event never fans out to Alice's endpoint")
    void fanOutIsScoped() {
        endpointService.create(
                bob.tenantId(),
                new EndpointDtos.CreateRequest(
                        "bob-endpoint",
                        "https://bob.example.com/hook",
                        null,
                        Set.of("order.*"),
                        null, null, null, null, null, null, null));

        EventDtos.PublishResponse bobEvent =
                ingestion.ingest(
                        bob.tenantId(),
                        null,
                        new EventDtos.PublishRequest("order.created", objectMapper.createObjectNode()),
                        null,
                        Event.Source.API,
                        null);

        // Exactly one delivery, to Bob's endpoint — not two.
        assertThat(bobEvent.deliveries()).hasSize(1);
        assertThat(bobEvent.deliveries().getFirst().endpointId()).isNotEqualTo(aliceEndpointId);
    }
}

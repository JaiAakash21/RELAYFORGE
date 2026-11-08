package com.relayforge.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.relayforge.auth.AuthService;
import com.relayforge.auth.dto.AuthDtos;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryAttempt;
import com.relayforge.delivery.DeliveryAttemptRepository;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.DeliveryStatus;
import com.relayforge.endpoints.EndpointService;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.events.Event;
import com.relayforge.events.EventIngestionService;
import com.relayforge.events.dto.EventDtos;
import com.relayforge.security.WebhookSigner;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end pipeline tests against a real receiver.
 *
 * <h2>Context isolation</h2>
 *
 * This is the one test class that needs the workers running, so it overrides
 * the base class's {@code relayforge.role} to {@code all}. That gives it a
 * separate Spring context, and {@code @DirtiesContext} closes that context when
 * the class finishes.
 *
 * <p>Closing it matters: Spring caches contexts for the lifetime of the JVM, and
 * a cached context keeps its {@code @Scheduled} methods firing. Without the
 * dirty marker, this class's dispatcher and workers would carry on polling the
 * shared database while other test classes ran — claiming their fixture rows,
 * attempting delivery to hosts those classes never allowlisted, and failing
 * their assertions. The symptom is a suite that passes class by class and fails
 * when run together.
 *
 * <p>Each test publishes a real event and lets the ordinary machinery — the
 * transactional outbox, the Kafka relay, the dispatcher, the workers and the
 * retry engine — carry it to a WireMock receiver. Nothing is stubbed between
 * ingestion and the HTTP request, so these assert on the behaviour the platform
 * actually has rather than on the behaviour each component was mocked to have.
 */
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryPipelineIT extends IntegrationTestBase {

    /**
     * Started in a static initializer, not in {@code @BeforeAll}.
     *
     * <p>{@code @DynamicPropertySource} suppliers are evaluated while the Spring
     * context is being prepared, which happens before {@code @BeforeAll}. The
     * port has to exist by then, because it is added to the egress port
     * allowlist — otherwise every delivery is refused as "port not permitted".
     */
    private static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @Autowired private AuthService authService;
    @Autowired private EndpointService endpointService;
    @Autowired private EventIngestionService ingestion;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private DeliveryAttemptRepository attempts;
    @Autowired private WebhookSigner signer;
    @Autowired private ObjectMapper objectMapper;

    private UUID tenantId;

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        // WireMock binds on loopback and on an arbitrary high port, both of
        // which the SSRF policy refuses by default. Rather than disabling the
        // policy for these tests, the one host and the one port it uses are
        // added — the policy itself is exercised separately in
        // DestinationPolicyTest and is not weakened here.
        registry.add(
                "relayforge.egress.allowed-ports",
                () -> "80,443,8080,8081,8443,3000," + wireMock.port());

        // The workers are the subject of these tests, so this class — and only
        // this class — runs them.
        registry.add("relayforge.role", () -> "all");
    }

    @BeforeEach
    void setUp() {
        resetDatabase();
        wireMock.resetAll();

        AuthDtos.TokenResponse registration =
                authService.register(
                        new AuthDtos.RegisterRequest(
                                "pipeline-" + UUID.randomUUID() + "@test.dev",
                                "an-adequately-long-password",
                                "Pipeline Test",
                                "Pipeline Org"),
                        "integration-test");
        tenantId = registration.tenant().id();
    }

    private String receiverUrl(String path) {
        return "http://localhost:" + wireMock.port() + path;
    }

    private UUID createEndpoint(String path, int maxAttempts) {
        EndpointService.Created created =
                endpointService.create(
                        tenantId,
                        new EndpointDtos.CreateRequest(
                                "receiver",
                                receiverUrl(path),
                                null,
                                Set.of("order.*"),
                                maxAttempts,
                                500L, // short initial delay so tests finish quickly
                                2000L,
                                2000,
                                0.0, // jitter off: these tests assert on timing
                                null,
                                null));
        return created.endpoint().getId();
    }

    private UUID publish(String eventType) {
        EventDtos.PublishResponse response =
                ingestion.ingest(
                        tenantId,
                        null,
                        new EventDtos.PublishRequest(
                                eventType, objectMapper.createObjectNode().put("n", 1)),
                        null,
                        Event.Source.API,
                        null);
        assertThat(response.deliveries()).hasSize(1);
        return response.deliveries().getFirst().deliveryId();
    }

    private Delivery awaitStatus(UUID deliveryId, DeliveryStatus expected, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () ->
                                assertThat(deliveries.findById(deliveryId).orElseThrow().getStatus())
                                        .isEqualTo(expected));
        return deliveries.findById(deliveryId).orElseThrow();
    }

    @Test
    @DisplayName("an event reaches the receiver, signed, through the whole pipeline")
    void happyPath() {
        wireMock.stubFor(post(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        createEndpoint("/ok", 5);

        UUID deliveryId = publish("order.created");
        Delivery delivered = awaitStatus(deliveryId, DeliveryStatus.SUCCESS, Duration.ofSeconds(30));

        assertThat(delivered.getAttemptCount()).isEqualTo(1);
        assertThat(delivered.getLastResponseCode()).isEqualTo(200);
        assertThat(delivered.getCompletedAt()).isNotNull();

        // The headers a receiver needs in order to verify and deduplicate.
        wireMock.verify(
                postRequestedFor(urlPathEqualTo("/ok"))
                        .withHeader("X-RelayForge-Signature", matching("^t=\\d+,v1=[0-9a-f]{64}$"))
                        .withHeader("X-RelayForge-Event-Id", matching("^[0-9a-f-]{36}$"))
                        .withHeader("X-RelayForge-Delivery-Id", equalTo(deliveryId.toString()))
                        .withHeader("X-RelayForge-Attempt", equalTo("1"))
                        .withHeader("X-RelayForge-Event-Type", equalTo("order.created")));
    }

    @Test
    @DisplayName("the signature verifies against the endpoint's secret and the exact body sent")
    void signatureVerifies() {
        wireMock.stubFor(post(urlPathEqualTo("/signed")).willReturn(aResponse().withStatus(200)));

        EndpointService.Created created =
                endpointService.create(
                        tenantId,
                        new EndpointDtos.CreateRequest(
                                "signed",
                                receiverUrl("/signed"),
                                null,
                                Set.of("order.*"),
                                3,
                                500L,
                                2000L,
                                2000,
                                0.0,
                                null,
                                null));
        String plaintextSecret = created.plaintextSecret();

        UUID deliveryId = publish("order.created");
        awaitStatus(deliveryId, DeliveryStatus.SUCCESS, Duration.ofSeconds(30));

        var requests = wireMock.findAll(postRequestedFor(urlPathEqualTo("/signed")));
        assertThat(requests).hasSize(1);
        var request = requests.getFirst();

        // Verify exactly as an integrator would, using the documented algorithm
        // over the raw bytes received. If the signer and the published
        // documentation ever diverge, this fails.
        boolean valid =
                signer.verify(
                        plaintextSecret,
                        request.getHeader("X-RelayForge-Signature"),
                        request.getBody(),
                        java.time.Instant.now(),
                        300);
        assertThat(valid).as("the receiver must be able to verify the signature").isTrue();

        // And a wrong secret must not verify.
        assertThat(
                        signer.verify(
                                "whsec_wrong",
                                request.getHeader("X-RelayForge-Signature"),
                                request.getBody(),
                                java.time.Instant.now(),
                                300))
                .isFalse();
    }

    @Test
    @DisplayName("transient 500s are retried with growing delays, then succeed")
    void retriesThenSucceeds() {
        // WireMock scenario: fail twice, then succeed.
        wireMock.stubFor(
                post(urlPathEqualTo("/flaky"))
                        .inScenario("flaky")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(500))
                        .willSetStateTo("attempt-2"));
        wireMock.stubFor(
                post(urlPathEqualTo("/flaky"))
                        .inScenario("flaky")
                        .whenScenarioStateIs("attempt-2")
                        .willReturn(aResponse().withStatus(500))
                        .willSetStateTo("recovered"));
        wireMock.stubFor(
                post(urlPathEqualTo("/flaky"))
                        .inScenario("flaky")
                        .whenScenarioStateIs("recovered")
                        .willReturn(aResponse().withStatus(200)));

        createEndpoint("/flaky", 5);
        UUID deliveryId = publish("order.created");

        Delivery delivered =
                awaitStatus(deliveryId, DeliveryStatus.SUCCESS, Duration.ofSeconds(60));
        assertThat(delivered.getAttemptCount()).isEqualTo(3);

        List<DeliveryAttempt> history =
                attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).getResponseStatus()).isEqualTo(500);
        assertThat(history.get(1).getResponseStatus()).isEqualTo(500);
        assertThat(history.get(2).getResponseStatus()).isEqualTo(200);

        // Exponential backoff: the second gap must exceed the first. With
        // jitter disabled this is deterministic.
        long firstGap =
                Duration.between(history.get(0).getCompletedAt(), history.get(1).getStartedAt())
                        .toMillis();
        long secondGap =
                Duration.between(history.get(1).getCompletedAt(), history.get(2).getStartedAt())
                        .toMillis();
        assertThat(secondGap)
                .as("the delay must grow between attempts (%dms then %dms)", firstGap, secondGap)
                .isGreaterThan(firstGap);
    }

    @Test
    @DisplayName("a permanently failing receiver dead-letters with its history intact")
    void exhaustsAndDeadLetters() {
        wireMock.stubFor(post(urlPathEqualTo("/broken")).willReturn(aResponse().withStatus(503)));
        createEndpoint("/broken", 3);

        UUID deliveryId = publish("order.created");
        Delivery failed = awaitStatus(deliveryId, DeliveryStatus.FAILED, Duration.ofSeconds(60));

        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getLastResponseCode()).isEqualTo(503);
        assertThat(failed.getLastError()).contains("budget exhausted");

        // Every failure is preserved — this is the evidence a dead letter is for.
        assertThat(attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).hasSize(3);
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo("/broken")))).hasSize(3);
    }

    @Test
    @DisplayName("a non-retryable 4xx fails immediately without burning the budget")
    void terminalFailureStopsAtOnce() {
        wireMock.stubFor(post(urlPathEqualTo("/bad")).willReturn(aResponse().withStatus(400)));
        createEndpoint("/bad", 5);

        UUID deliveryId = publish("order.created");
        Delivery failed = awaitStatus(deliveryId, DeliveryStatus.FAILED, Duration.ofSeconds(30));

        // One attempt, not five: repeating an identical request the receiver
        // rejected cannot change the answer, and dead-lettering surfaces it to
        // a human immediately.
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo("/bad")))).hasSize(1);
    }

    @Test
    @DisplayName("a slow receiver times out and is recorded with no HTTP status")
    void slowReceiverTimesOut() {
        // The endpoint's per-attempt timeout is 2s; the receiver takes 5s.
        wireMock.stubFor(
                post(urlPathEqualTo("/slow"))
                        .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));
        createEndpoint("/slow", 2);

        UUID deliveryId = publish("order.created");
        Delivery failed = awaitStatus(deliveryId, DeliveryStatus.FAILED, Duration.ofSeconds(60));

        List<DeliveryAttempt> history =
                attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        assertThat(history).isNotEmpty();
        DeliveryAttempt first = history.getFirst();

        // No status at all — this is the case a UI must not render as a blank
        // cell, because "no response" is a different fact from "missing data".
        assertThat(first.getResponseStatus()).isNull();
        assertThat(first.getErrorClass().name()).contains("TIMEOUT");
        // The attempt is bounded by the configured timeout, not by the
        // receiver's delay: a slow endpoint cannot pin a worker.
        assertThat(first.getDurationMs()).isLessThan(5000);
        assertThat(failed.getLastResponseCode()).isNull();
    }

    @Test
    @DisplayName("Retry-After overrides the computed backoff")
    void honoursRetryAfter() {
        wireMock.stubFor(
                post(urlPathEqualTo("/throttled"))
                        .inScenario("throttle")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(
                                aResponse().withStatus(429).withHeader("Retry-After", "3"))
                        .willSetStateTo("ok"));
        wireMock.stubFor(
                post(urlPathEqualTo("/throttled"))
                        .inScenario("throttle")
                        .whenScenarioStateIs("ok")
                        .willReturn(aResponse().withStatus(200)));

        createEndpoint("/throttled", 4);
        UUID deliveryId = publish("order.created");
        awaitStatus(deliveryId, DeliveryStatus.SUCCESS, Duration.ofSeconds(60));

        List<DeliveryAttempt> history =
                attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().getRetryAfterSeconds()).isEqualTo(3);

        // The endpoint's own backoff would be ~500ms. The receiver asked for
        // 3s, and the receiver knows its capacity better than our formula does.
        long gap =
                Duration.between(history.get(0).getCompletedAt(), history.get(1).getStartedAt())
                        .toMillis();
        assertThat(gap)
                .as("the gap must follow Retry-After, not the 500ms backoff")
                .isGreaterThanOrEqualTo(2500L);
    }

    @Test
    @DisplayName("an idempotency key makes a repeated publish a no-op")
    void idempotencyPreventsDuplicates() {
        wireMock.stubFor(post(urlPathEqualTo("/idem")).willReturn(aResponse().withStatus(200)));
        createEndpoint("/idem", 3);

        EventDtos.PublishRequest request =
                new EventDtos.PublishRequest(
                        "order.created", objectMapper.createObjectNode().put("n", 1));

        EventDtos.PublishResponse first =
                ingestion.ingest(tenantId, null, request, "same-key", Event.Source.API, null);
        EventDtos.PublishResponse second =
                ingestion.ingest(tenantId, null, request, "same-key", Event.Source.API, null);

        assertThat(first.deduplicated()).isFalse();
        assertThat(second.deduplicated()).isTrue();
        assertThat(second.eventId()).isEqualTo(first.eventId());

        awaitStatus(
                first.deliveries().getFirst().deliveryId(),
                DeliveryStatus.SUCCESS,
                Duration.ofSeconds(30));

        // One webhook, not two — which is the point of the header.
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo("/idem")))).hasSize(1);
    }

    @Test
    @DisplayName("an event with no subscribers is stored rather than rejected")
    void unsubscribedEventIsStored() {
        createEndpoint("/ok", 3); // subscribes to order.* only

        EventDtos.PublishResponse response =
                ingestion.ingest(
                        tenantId,
                        null,
                        new EventDtos.PublishRequest(
                                "invoice.paid", objectMapper.createObjectNode()),
                        null,
                        Event.Source.API,
                        null);

        // Accepted and recorded: the event is real history, and a subscription
        // added later can be satisfied by replaying it.
        assertThat(response.eventId()).isNotNull();
        assertThat(response.deliveriesCreated()).isZero();
    }

    @Test
    @DisplayName("a paused endpoint holds deliveries rather than discarding them")
    void pauseHoldsDeliveries() {
        wireMock.stubFor(post(urlPathEqualTo("/paused")).willReturn(aResponse().withStatus(200)));
        UUID endpointId = createEndpoint("/paused", 5);
        endpointService.pause(tenantId, endpointId);

        UUID deliveryId = publish("order.created");

        // Held, not cancelled: pausing is how a user takes a receiver down for
        // maintenance, and it would be useless if it dropped everything.
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(6))
                .untilAsserted(
                        () ->
                                assertThat(
                                                deliveries
                                                        .findById(deliveryId)
                                                        .orElseThrow()
                                                        .getStatus())
                                        .isNotIn(DeliveryStatus.SUCCESS, DeliveryStatus.FAILED));
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo("/paused")))).isEmpty();

        endpointService.resume(tenantId, endpointId);
        awaitStatus(deliveryId, DeliveryStatus.SUCCESS, Duration.ofSeconds(45));
    }
}

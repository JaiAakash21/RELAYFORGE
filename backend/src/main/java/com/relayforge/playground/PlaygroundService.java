package com.relayforge.playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayforge.common.Ids;
import com.relayforge.common.NotFoundException;
import com.relayforge.common.ValidationException;
import com.relayforge.config.RelayForgeProperties;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryQueryService;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.DeliveryStatus;
import com.relayforge.delivery.dto.DeliveryDtos;
import com.relayforge.endpoints.EndpointService;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.endpoints.WebhookEndpointRepository;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.events.Event;
import com.relayforge.events.EventIngestionService;
import com.relayforge.events.dto.EventDtos;
import com.relayforge.playground.dto.PlaygroundDtos;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the Reliability Playground.
 *
 * <h2>What makes this a real demonstration and not an animation</h2>
 *
 * A run does exactly what a production integration does, with no special-casing
 * anywhere in the pipeline:
 *
 * <ol>
 *   <li>Configure the simulator to fail in a specific, chosen way.
 *   <li>Create (or reuse) a genuine webhook endpoint pointing at that simulator
 *       target, with the normal AES-GCM-sealed signing secret.
 *   <li>Publish a genuine event through {@link EventIngestionService} — same
 *       transaction, same outbox rows, same Kafka relay.
 *   <li>Let the ordinary workers, retry engine and dead-letter logic handle it.
 *   <li>Report the delivery and its recorded attempts back to the UI.
 * </ol>
 *
 * <p>Nothing here fakes a status, injects a synthetic attempt, or shortcuts the
 * worker. The timeline the user watches is a read of {@code delivery_attempts} —
 * so if the retry engine were broken, the playground would show it rather than
 * hide it. That is the only kind of demonstration worth building.
 */
@Service
public class PlaygroundService {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundService.class);

    /** Event type used by playground runs, so they can be filtered out of real history. */
    private static final String EVENT_TYPE = "relayforge.playground.test";

    private final PlaygroundRunRepository runs;
    private final SimulatorClient simulator;
    private final EndpointService endpointService;
    private final WebhookEndpointRepository endpoints;
    private final EventIngestionService ingestion;
    private final DeliveryRepository deliveries;
    private final DeliveryQueryService deliveryQuery;
    private final ObjectMapper objectMapper;
    private final RelayForgeProperties properties;

    public PlaygroundService(
            PlaygroundRunRepository runs,
            SimulatorClient simulator,
            EndpointService endpointService,
            WebhookEndpointRepository endpoints,
            EventIngestionService ingestion,
            DeliveryRepository deliveries,
            DeliveryQueryService deliveryQuery,
            ObjectMapper objectMapper,
            RelayForgeProperties properties) {
        this.runs = runs;
        this.simulator = simulator;
        this.endpointService = endpointService;
        this.endpoints = endpoints;
        this.ingestion = ingestion;
        this.deliveries = deliveries;
        this.deliveryQuery = deliveryQuery;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<PlaygroundDtos.Scenario> scenarios() {
        return PlaygroundScenarios.all();
    }

    /** Starts a run. */
    @Transactional
    public PlaygroundDtos.RunResponse startRun(
            UUID tenantId, UUID userId, PlaygroundDtos.StartRunRequest request) {

        PlaygroundDtos.Scenario scenario =
                PlaygroundScenarios.byId(request.scenarioId())
                        .orElseThrow(
                                () ->
                                        new ValidationException(
                                                "unknown_scenario",
                                                "There is no playground scenario with id '"
                                                        + request.scenarioId()
                                                        + "'"));
        String simulatorType =
                PlaygroundScenarios.simulatorType(scenario.id())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Scenario "
                                                        + scenario.id()
                                                        + " has no simulator mapping"));

        // Resolved parameters: the request's values where present, the scenario's
        // documented defaults otherwise.
        PlaygroundDtos.Defaults defaults = scenario.defaults();
        int failureCount = orDefault(request.failureCount(), defaults.failureCount());
        int statusCode = orDefault(request.statusCode(), defaults.statusCode());
        long delayMs = orDefault(request.delayMs(), defaults.delayMs());
        int retryAfterSeconds = orDefault(request.retryAfterSeconds(), defaults.retryAfterSeconds());
        int recoverAfterSeconds =
                orDefault(request.recoverAfterSeconds(), defaults.recoverAfterSeconds());
        int maxAttempts = orDefault(request.maxAttempts(), defaults.maxAttempts());

        String token = "pg" + Ids.randomToken(12).replaceAll("[^A-Za-z0-9]", "");

        Map<String, Object> simulatorConfig = new LinkedHashMap<>();
        simulatorConfig.put("type", simulatorType);
        simulatorConfig.put("failureCount", failureCount);
        simulatorConfig.put("statusCode", statusCode);
        simulatorConfig.put("delayMs", delayMs);
        simulatorConfig.put("retryAfterSeconds", retryAfterSeconds);
        simulatorConfig.put("recoverAfterSeconds", recoverAfterSeconds);

        // Configure the simulator before publishing. The other order would race:
        // a worker could deliver before the fault injection existed and the first
        // attempt would succeed against an unconfigured target.
        simulator.configureTarget(token, simulatorConfig);

        ObjectNode configNode = objectMapper.valueToTree(simulatorConfig);
        configNode.put("maxAttempts", maxAttempts);

        PlaygroundRun run =
                new PlaygroundRun(
                        Ids.newId(),
                        tenantId,
                        scenario.id(),
                        scenario.name(),
                        configNode,
                        token,
                        userId);

        WebhookEndpoint endpoint = createRunEndpoint(tenantId, scenario, token, maxAttempts);
        run.setEndpointId(endpoint.getId());

        JsonNode payload = request.payload() != null ? request.payload() : defaultPayload(scenario, token);

        EventDtos.PublishResponse published =
                ingestion.ingest(
                        tenantId,
                        null,
                        new EventDtos.PublishRequest(EVENT_TYPE, payload),
                        null,
                        Event.Source.PLAYGROUND,
                        endpoint.getId());

        run.setEventId(published.eventId());
        if (!published.deliveries().isEmpty()) {
            run.setDeliveryId(published.deliveries().getFirst().deliveryId());
        }
        runs.save(run);

        log.info(
                "Started playground run {} (scenario {}) for tenant {} against simulator target {}",
                run.getId(),
                scenario.id(),
                tenantId,
                token);

        return describe(run, scenario);
    }

    /**
     * Reads a run's current state.
     *
     * <p>Polled by the UI roughly once a second. Polling was chosen over
     * server-sent events deliberately: a run is a handful of rows, the query is
     * indexed and trivial, and polling survives a page reload, a worker restart
     * and a proxy that buffers responses — none of which an SSE stream does
     * without extra reconnection machinery. The simpler mechanism that cannot
     * silently stop working is the better one here.
     */
    @Transactional
    public PlaygroundDtos.RunResponse getRun(UUID tenantId, UUID runId) {
        PlaygroundRun run =
                runs.findByIdAndTenantId(runId, tenantId)
                        .orElseThrow(() -> new NotFoundException("Playground run", runId));
        PlaygroundDtos.Scenario scenario =
                PlaygroundScenarios.byId(run.getScenarioId()).orElse(null);

        // Settle the run's own status from the delivery's terminal state, so a
        // completed run stays completed even after the delivery rows are pruned.
        if (run.getStatus() == PlaygroundRun.Status.RUNNING && run.getDeliveryId() != null) {
            deliveries
                    .findByIdAndTenantId(run.getDeliveryId(), tenantId)
                    .ifPresent(
                            delivery -> {
                                if (delivery.getStatus() == DeliveryStatus.SUCCESS) {
                                    run.complete(
                                            PlaygroundRun.Status.SUCCEEDED,
                                            "Delivered successfully after "
                                                    + delivery.getAttemptCount()
                                                    + " attempt(s).");
                                    runs.save(run);
                                } else if (delivery.getStatus() == DeliveryStatus.FAILED) {
                                    run.complete(
                                            PlaygroundRun.Status.DEAD_LETTERED,
                                            "Dead-lettered after "
                                                    + delivery.getAttemptCount()
                                                    + " attempt(s). The delivery is replayable from"
                                                    + " the dead-letter queue with its full history"
                                                    + " intact.");
                                    runs.save(run);
                                } else if (delivery.getStatus() == DeliveryStatus.CANCELLED) {
                                    run.complete(
                                            PlaygroundRun.Status.CANCELLED,
                                            "The delivery was cancelled.");
                                    runs.save(run);
                                }
                            });
        }
        return describe(run, scenario);
    }

    @Transactional(readOnly = true)
    public List<PlaygroundDtos.RunSummary> recentRuns(UUID tenantId) {
        return runs.findTop20ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(
                        run ->
                                new PlaygroundDtos.RunSummary(
                                        run.getId(),
                                        run.getScenarioId(),
                                        run.getScenarioName(),
                                        run.getStatus().name(),
                                        run.getDeliveryId(),
                                        run.getCreatedAt(),
                                        run.getCompletedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- internals

    /**
     * Creates the endpoint for this run.
     *
     * <p>A fresh endpoint per run, not a shared one, so concurrent runs cannot
     * interfere with each other's attempt counters or retry policies. The retry
     * policy is tightened to playground values — short delays and a small budget
     * — because a demo that takes the production schedule's twenty minutes to
     * dead-letter is not a demo anyone watches.
     */
    private WebhookEndpoint createRunEndpoint(
            UUID tenantId, PlaygroundDtos.Scenario scenario, String token, int maxAttempts) {

        RelayForgeProperties.Playground config = properties.playground();
        EndpointDtos.CreateRequest request =
                new EndpointDtos.CreateRequest(
                        "Playground · " + scenario.name(),
                        simulator.receiveUrl(token),
                        "Created automatically by the Reliability Playground. Safe to delete.",
                        Set.of(EVENT_TYPE),
                        maxAttempts,
                        config.initialDelay().toMillis(),
                        config.maxDelay().toMillis(),
                        // A short per-attempt timeout so the slow-response
                        // scenario actually times out within a watchable window.
                        5_000,
                        // Jitter is kept on: the point is to show the real
                        // algorithm, and switching it off for the demo would
                        // misrepresent what production does.
                        0.2,
                        null,
                        null);
        return endpointService.create(tenantId, request).endpoint();
    }

    private JsonNode defaultPayload(PlaygroundDtos.Scenario scenario, String token) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("scenario", scenario.id());
        payload.put("simulatorToken", token);
        payload.put("note", "Published by the RelayForge Reliability Playground.");
        payload.put("publishedAt", Instant.now().toString());
        return payload;
    }

    /** Assembles the run view from the platform's real records. */
    private PlaygroundDtos.RunResponse describe(
            PlaygroundRun run, PlaygroundDtos.Scenario scenario) {

        DeliveryDtos.Response deliveryResponse = null;
        List<DeliveryDtos.AttemptResponse> attempts = List.of();
        Long secondsUntilNextAttempt = null;

        if (run.getDeliveryId() != null) {
            Delivery delivery =
                    deliveries.findByIdAndTenantId(run.getDeliveryId(), run.getTenantId()).orElse(null);
            if (delivery != null) {
                WebhookEndpoint endpoint =
                        endpoints.findById(delivery.getEndpointId()).orElse(null);
                deliveryResponse =
                        DeliveryDtos.Response.from(
                                delivery,
                                endpoint == null ? null : endpoint.getName(),
                                endpoint == null ? null : endpoint.getUrl(),
                                EVENT_TYPE);
                attempts = deliveryQuery.attempts(run.getTenantId(), delivery.getId());

                if (delivery.getNextAttemptAt() != null
                        && delivery.getStatus() == DeliveryStatus.RETRY_SCHEDULED) {
                    long seconds =
                            Duration.between(Instant.now(), delivery.getNextAttemptAt()).toSeconds();
                    secondsUntilNextAttempt = Math.max(0, seconds);
                }
            }
        }

        SimulatorClient.TargetView target = simulator.inspectTarget(run.getSimulatorToken());

        return new PlaygroundDtos.RunResponse(
                run.getId(),
                run.getScenarioId(),
                run.getScenarioName(),
                run.getConfig(),
                run.getStatus().name(),
                run.getOutcomeNote() != null
                        ? run.getOutcomeNote()
                        : scenario == null ? null : scenario.expectedOutcome(),
                run.getEndpointId(),
                run.getEventId(),
                run.getDeliveryId(),
                simulator.receivePath(run.getSimulatorToken()),
                deliveryResponse,
                attempts,
                target == null
                        ? new PlaygroundDtos.SimulatorView(null, null, null)
                        : new PlaygroundDtos.SimulatorView(
                                target.requestCount(), target.failures(), target.successes()),
                secondsUntilNextAttempt,
                run.getCreatedAt(),
                run.getCompletedAt());
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long orDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }
}

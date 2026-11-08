package com.relayforge.simulator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulator's HTTP surface.
 *
 * <p>Two kinds of route:
 *
 * <ul>
 *   <li>{@code /targets/**} — control plane, used by RelayForge's playground
 *       service to configure and inspect a scenario.
 *   <li>{@code /receive/{token}} — the webhook endpoint RelayForge actually
 *       delivers to. Its behaviour is whatever the token's scenario says.
 * </ul>
 */
@RestController
@RequestMapping("/simulator")
public class SimulatorController {

    private static final Logger log = LoggerFactory.getLogger(SimulatorController.class);

    private final SimulatorRegistry registry;

    public SimulatorController(SimulatorRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------ control plane

    public record ConfigureRequest(@Valid ScenarioConfig config) {}

    public record TargetView(
            String token,
            ScenarioConfig config,
            int requestCount,
            int failures,
            int successes,
            Instant createdAt,
            Instant firstRequestAt,
            Instant lastRequestAt,
            String receivePath) {

        static TargetView of(TargetState state) {
            return new TargetView(
                    state.token(),
                    state.config(),
                    state.requestCount(),
                    state.failures(),
                    state.successes(),
                    state.createdAt(),
                    state.firstRequestAt(),
                    state.lastRequestAt(),
                    "/simulator/receive/" + state.token());
        }
    }

    @PutMapping("/targets/{token}")
    public TargetView configure(
            @PathVariable String token, @Valid @RequestBody ConfigureRequest request) {
        ScenarioConfig config =
                request.config() == null
                        ? ScenarioConfig.defaultsFor(ScenarioType.SUCCESS)
                        : request.config();
        TargetState state = registry.configure(sanitiseToken(token), config);
        log.info("Configured simulator target {} as {}", state.token(), config.type());
        return TargetView.of(state);
    }

    @GetMapping("/targets/{token}")
    public ResponseEntity<TargetView> inspect(@PathVariable String token) {
        return registry
                .find(sanitiseToken(token))
                .map(state -> ResponseEntity.ok(TargetView.of(state)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/targets")
    public List<TargetView> list() {
        return registry.all().stream().map(TargetView::of).toList();
    }

    @DeleteMapping("/targets/{token}")
    public ResponseEntity<Void> delete(@PathVariable String token) {
        return registry.remove(sanitiseToken(token))
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return java.util.Arrays.stream(ScenarioType.values())
                .map(
                        type -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("type", type.name());
                            entry.put("defaults", ScenarioConfig.defaultsFor(type));
                            return entry;
                        })
                .toList();
    }

    // ------------------------------------------------------------- data plane

    /**
     * The webhook receiver.
     *
     * <p>An unconfigured token returns 200. That is deliberate: a token whose
     * scenario has been evicted should look like a healthy endpoint rather than
     * start failing for reasons unrelated to the scenario under test, which would
     * make an old demo link produce misleading results.
     */
    @PostMapping("/receive/{token}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String token,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {

        String safeToken = sanitiseToken(token);
        Optional<TargetState> found = registry.find(safeToken);
        if (found.isEmpty()) {
            return ok(safeToken, 0, "No scenario is configured for this token; responding 200.");
        }

        TargetState state = found.get();
        int attempt = state.registerRequest();
        ScenarioConfig config = state.config();

        log.info(
                "Simulator target {} received attempt {} (scenario {}, signature present: {})",
                safeToken,
                attempt,
                config.type(),
                request.getHeader("X-RelayForge-Signature") != null);

        return switch (config.type()) {
            case SUCCESS -> {
                state.recordSuccess();
                yield ok(safeToken, attempt, "Delivered.");
            }

            case TEMPORARY_SERVER_ERROR -> {
                if (attempt <= config.failureCount()) {
                    state.recordFailure();
                    yield failure(
                            config.statusCode(),
                            safeToken,
                            attempt,
                            "Simulated transient server error "
                                    + attempt
                                    + " of "
                                    + config.failureCount()
                                    + ". The next attempt after this count succeeds.");
                }
                state.recordSuccess();
                yield ok(
                        safeToken,
                        attempt,
                        "Recovered after " + config.failureCount() + " simulated failures.");
            }

            case RATE_LIMITED -> {
                if (attempt <= config.failureCount()) {
                    state.recordFailure();
                    // Retry-After is the point of this scenario: it should
                    // override RelayForge's computed backoff.
                    yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .header(
                                    HttpHeaders.RETRY_AFTER,
                                    Integer.toString(config.retryAfterSeconds()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(
                                    payload(
                                            safeToken,
                                            attempt,
                                            "Simulated rate limit. Retry-After: "
                                                    + config.retryAfterSeconds()
                                                    + "s."));
                }
                state.recordSuccess();
                yield ok(safeToken, attempt, "Rate limit cleared.");
            }

            case SLOW_RESPONSE -> {
                try {
                    Thread.sleep(config.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // If RelayForge's timeout is shorter than delayMs — which is the
                // default — the client has already given up and this response is
                // discarded. That is exactly what the scenario demonstrates.
                state.recordSuccess();
                yield ok(
                        safeToken,
                        attempt,
                        "Responded after a deliberate " + config.delayMs() + " ms delay.");
            }

            case PERMANENT_FAILURE -> {
                state.recordFailure();
                yield failure(
                        config.statusCode(),
                        safeToken,
                        attempt,
                        "Simulated permanent failure. This endpoint never succeeds, so the"
                                + " delivery will exhaust its attempts and dead-letter.");
            }

            case CONNECTION_DROP -> {
                state.recordFailure();
                // Abort without a response so the client sees a transport error
                // rather than an HTTP status. Testing the no-status code path
                // requires an actual absence of a status — a 502 would not do.
                throw new ConnectionDropException(
                        "Simulated connection drop for target " + safeToken);
            }

            case RECOVERY_AFTER_OUTAGE -> {
                if (!state.outageWindowElapsed()) {
                    state.recordFailure();
                    yield failure(
                            config.statusCode(),
                            safeToken,
                            attempt,
                            "Simulated outage. This endpoint recovers "
                                    + config.recoverAfterSeconds()
                                    + "s after the first attempt.");
                }
                state.recordSuccess();
                yield ok(
                        safeToken,
                        attempt,
                        "Recovered after the " + config.recoverAfterSeconds() + "s outage window.");
            }
        };
    }

    /** Thrown to abort the response without a status, simulating a dropped connection. */
    static class ConnectionDropException extends RuntimeException {
        ConnectionDropException(String message) {
            super(message);
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(
            String token, int attempt, String message) {
        return ResponseEntity.ok(payload(token, attempt, message));
    }

    private static ResponseEntity<Map<String, Object>> failure(
            int status, String token, int attempt, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload(token, attempt, message));
    }

    private static Map<String, Object> payload(String token, int attempt, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("simulator", true);
        body.put("token", token);
        body.put("attempt", attempt);
        body.put("message", message);
        body.put("at", Instant.now().toString());
        return body;
    }

    /**
     * Restricts a token to a safe character set.
     *
     * <p>The token is echoed back in responses and written to logs, so letting
     * arbitrary input through would allow log injection and reflected content.
     */
    private static String sanitiseToken(String token) {
        if (token == null) {
            return "unknown";
        }
        String trimmed = token.length() > 80 ? token.substring(0, 80) : token;
        return trimmed.replaceAll("[^A-Za-z0-9_-]", "");
    }
}

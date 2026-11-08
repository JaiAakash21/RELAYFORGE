package com.relayforge.playground;

import com.relayforge.playground.dto.PlaygroundDtos;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogue of Reliability Playground scenarios.
 *
 * <p>Defined in code rather than in the database because they are part of the
 * product, not tenant data: every tenant sees the same scenarios, they change
 * only when the code changes, and a migration to add one would be ceremony with
 * no benefit.
 *
 * <p>Each entry carries a {@code teaches} and an {@code expectedOutcome} string.
 * That is the difference between a demo that looks impressive and one someone can
 * actually follow: the UI states beforehand what the backend should do, then shows
 * what it did.
 */
public final class PlaygroundScenarios {

    private PlaygroundScenarios() {}

    private static final PlaygroundDtos.Parameter MAX_ATTEMPTS =
            new PlaygroundDtos.Parameter(
                    "maxAttempts",
                    "Attempt budget",
                    "integer",
                    1,
                    10,
                    "Total attempts before the delivery is dead-lettered.");

    private static final List<PlaygroundDtos.Scenario> SCENARIOS =
            List.of(
                    new PlaygroundDtos.Scenario(
                            "success",
                            "Normal delivery",
                            "The receiver answers HTTP 200 straight away.",
                            "The baseline: a single signed request, one attempt recorded, and the"
                                    + " delivery resolved as SUCCESS. Use it to see what healthy"
                                    + " latency looks like before breaking anything.",
                            "One attempt, HTTP 200, delivery status SUCCESS.",
                            List.of(MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(0, 200, 0, 0, 0, 5)),
                    new PlaygroundDtos.Scenario(
                            "temporary-server-error",
                            "Transient 500s, then recovery",
                            "The receiver returns HTTP 500 for a configurable number of attempts,"
                                    + " then succeeds.",
                            "That 5xx is classified as retryable, and that the gap between"
                                    + " attempts grows exponentially with jitter rather than"
                                    + " hammering a struggling receiver at a fixed interval. Watch"
                                    + " the delay roughly double between attempts.",
                            "N failed attempts with increasing gaps, then HTTP 200 and SUCCESS."
                                    + " Nothing is lost.",
                            List.of(
                                    new PlaygroundDtos.Parameter(
                                            "failureCount",
                                            "Failures before recovery",
                                            "integer",
                                            1,
                                            10,
                                            "How many attempts return 500 before the receiver"
                                                    + " starts succeeding."),
                                    MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(2, 500, 0, 0, 0, 5)),
                    new PlaygroundDtos.Scenario(
                            "rate-limited",
                            "HTTP 429 with Retry-After",
                            "The receiver rate-limits us and states how long to wait.",
                            "That a receiver's own Retry-After header takes precedence over the"
                                    + " computed backoff. The receiver knows its capacity better"
                                    + " than our formula does, so the scheduled delay should match"
                                    + " the header rather than the exponential curve.",
                            "Attempts spaced by the Retry-After value, not by exponential"
                                    + " backoff, then SUCCESS.",
                            List.of(
                                    new PlaygroundDtos.Parameter(
                                            "retryAfterSeconds",
                                            "Retry-After (seconds)",
                                            "integer",
                                            1,
                                            60,
                                            "The value the receiver asks us to honour."),
                                    new PlaygroundDtos.Parameter(
                                            "failureCount",
                                            "429 responses",
                                            "integer",
                                            1,
                                            10,
                                            "How many attempts are rate-limited."),
                                    MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(2, 429, 0, 3, 0, 5)),
                    new PlaygroundDtos.Scenario(
                            "slow-response",
                            "Slow receiver",
                            "The receiver delays its response past our timeout.",
                            "That a slow endpoint cannot hold a worker indefinitely. The"
                                    + " per-attempt response timeout fires, the attempt is recorded"
                                    + " as READ_TIMEOUT with no HTTP status at all, and the worker"
                                    + " moves on. Compare the recorded duration with the configured"
                                    + " timeout.",
                            "Attempts failing with READ_TIMEOUT and no response status, retried"
                                    + " until the budget is spent.",
                            List.of(
                                    new PlaygroundDtos.Parameter(
                                            "delayMs",
                                            "Response delay (ms)",
                                            "integer",
                                            0,
                                            60_000,
                                            "Set this above the endpoint's timeout to force a"
                                                    + " timeout, or below it to see a slow success."),
                                    MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(0, 200, 8_000, 0, 0, 3)),
                    new PlaygroundDtos.Scenario(
                            "permanent-failure",
                            "Permanently broken receiver",
                            "The receiver always fails, whatever we do.",
                            "The whole dead-letter path. Retries continue until the attempt budget"
                                    + " is exhausted, the delivery moves to FAILED, and the full"
                                    + " attempt history is preserved so it can be replayed by hand"
                                    + " once the receiver is fixed.",
                            "Every attempt fails, the delivery ends FAILED and appears in the"
                                    + " dead-letter queue, replayable with its history intact.",
                            List.of(
                                    new PlaygroundDtos.Parameter(
                                            "statusCode",
                                            "Status code",
                                            "integer",
                                            400,
                                            599,
                                            "Try 500 to see the full retry schedule, or 400 to see"
                                                    + " an immediate terminal failure — a 4xx is"
                                                    + " not retried, because repeating the same"
                                                    + " request cannot change the answer."),
                                    MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(0, 500, 0, 0, 0, 3)),
                    new PlaygroundDtos.Scenario(
                            "connection-drop",
                            "Dropped connection",
                            "The receiver accepts the connection and then closes it without"
                                    + " responding.",
                            "The transport-failure path, where there is no HTTP status to"
                                    + " classify. The attempt is recorded with an error class and"
                                    + " no status code, and is treated as retryable because nothing"
                                    + " ever reached the receiving application.",
                            "Attempts recorded with a transport error and no response status,"
                                    + " retried on the normal schedule.",
                            List.of(MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(0, 500, 0, 0, 0, 3)),
                    new PlaygroundDtos.Scenario(
                            "recovery-after-outage",
                            "Outage, then recovery",
                            "The receiver is down for a fixed period, then comes back.",
                            "That recovery is time-based rather than attempt-based. The retry"
                                    + " schedule has to still be running when the receiver returns"
                                    + " — which is exactly why the attempt budget and the maximum"
                                    + " backoff matter. Set the outage longer than the budget"
                                    + " allows and watch it dead-letter instead.",
                            "Failures during the outage window, then a successful attempt once it"
                                    + " elapses — provided the attempt budget lasts that long.",
                            List.of(
                                    new PlaygroundDtos.Parameter(
                                            "recoverAfterSeconds",
                                            "Outage length (seconds)",
                                            "integer",
                                            5,
                                            300,
                                            "Measured from the first attempt."),
                                    MAX_ATTEMPTS),
                            new PlaygroundDtos.Defaults(0, 503, 0, 0, 20, 6)));

    private static final Map<String, PlaygroundDtos.Scenario> BY_ID =
            SCENARIOS.stream()
                    .collect(
                            java.util.stream.Collectors.toMap(
                                    PlaygroundDtos.Scenario::id, scenario -> scenario));

    /** Maps a catalogue id onto the simulator's scenario type. */
    private static final Map<String, String> SIMULATOR_TYPES =
            Map.of(
                    "success", "SUCCESS",
                    "temporary-server-error", "TEMPORARY_SERVER_ERROR",
                    "rate-limited", "RATE_LIMITED",
                    "slow-response", "SLOW_RESPONSE",
                    "permanent-failure", "PERMANENT_FAILURE",
                    "connection-drop", "CONNECTION_DROP",
                    "recovery-after-outage", "RECOVERY_AFTER_OUTAGE");

    public static List<PlaygroundDtos.Scenario> all() {
        return SCENARIOS;
    }

    public static Optional<PlaygroundDtos.Scenario> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static Optional<String> simulatorType(String scenarioId) {
        return Optional.ofNullable(SIMULATOR_TYPES.get(scenarioId));
    }
}

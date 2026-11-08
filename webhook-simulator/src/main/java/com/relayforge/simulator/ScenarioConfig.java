package com.relayforge.simulator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * How one simulator target should behave.
 *
 * <p>Bounds on every field are not cosmetic: this service is reachable from the
 * dashboard, and an unbounded {@code delayMs} or {@code failureCount} would let
 * a user make a demo hang indefinitely or occupy a worker far longer than any
 * real receiver would.
 *
 * @param type           which failure mode to reproduce
 * @param failureCount   attempts to fail before succeeding (TEMPORARY_SERVER_ERROR)
 * @param statusCode     status to return (PERMANENT_FAILURE, RATE_LIMITED)
 * @param delayMs        response delay (SLOW_RESPONSE)
 * @param retryAfterSeconds value for the {@code Retry-After} header (RATE_LIMITED)
 * @param recoverAfterSeconds how long to fail before recovering (RECOVERY_AFTER_OUTAGE)
 */
public record ScenarioConfig(
        @NotNull ScenarioType type,
        @Min(0) @Max(20) int failureCount,
        @Min(100) @Max(599) int statusCode,
        @Min(0) @Max(120_000) long delayMs,
        @Min(0) @Max(300) int retryAfterSeconds,
        @Min(0) @Max(600) int recoverAfterSeconds) {

    /** Sensible defaults per scenario, so a caller can send only the type. */
    public static ScenarioConfig defaultsFor(ScenarioType type) {
        return switch (type) {
            case SUCCESS -> new ScenarioConfig(type, 0, 200, 0, 0, 0);
            // Two failures then success: enough to watch backoff grow, short
            // enough that a demo completes while someone is still watching.
            case TEMPORARY_SERVER_ERROR -> new ScenarioConfig(type, 2, 500, 0, 0, 0);
            case RATE_LIMITED -> new ScenarioConfig(type, 2, 429, 0, 3, 0);
            // 8 s against a 5 s default response timeout, so the timeout fires.
            case SLOW_RESPONSE -> new ScenarioConfig(type, 0, 200, 8_000, 0, 0);
            case PERMANENT_FAILURE -> new ScenarioConfig(type, 0, 500, 0, 0, 0);
            case CONNECTION_DROP -> new ScenarioConfig(type, 0, 500, 0, 0, 0);
            case RECOVERY_AFTER_OUTAGE -> new ScenarioConfig(type, 0, 503, 0, 0, 20);
        };
    }
}

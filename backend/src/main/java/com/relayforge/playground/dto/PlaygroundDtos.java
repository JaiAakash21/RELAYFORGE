package com.relayforge.playground.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayforge.delivery.dto.DeliveryDtos;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the Reliability Playground. */
public final class PlaygroundDtos {

    private PlaygroundDtos() {}

    /**
     * A scenario offered to the user.
     *
     * @param teaches what the scenario demonstrates about the platform — shown in
     *     the UI so the demonstration explains itself without the source code
     * @param expectedOutcome what should happen, so a viewer can tell whether the
     *     system behaved correctly rather than just watching it do something
     */
    public record Scenario(
            String id,
            String name,
            String description,
            String teaches,
            String expectedOutcome,
            List<Parameter> parameters,
            Defaults defaults) {}

    public record Parameter(
            String key, String label, String type, Number min, Number max, String help) {}

    public record Defaults(
            int failureCount,
            int statusCode,
            long delayMs,
            int retryAfterSeconds,
            int recoverAfterSeconds,
            int maxAttempts) {}

    public record StartRunRequest(
            @NotBlank String scenarioId,
            @Min(0) @Max(20) Integer failureCount,
            @Min(100) @Max(599) Integer statusCode,
            @Min(0) @Max(120_000) Long delayMs,
            @Min(0) @Max(300) Integer retryAfterSeconds,
            @Min(0) @Max(600) Integer recoverAfterSeconds,
            @Min(1) @Max(10) Integer maxAttempts,
            /** Optional custom payload; a sensible default is used when absent. */
            JsonNode payload) {}

    /**
     * A run and everything needed to narrate it.
     *
     * <p>{@code delivery} and {@code attempts} are the platform's real records,
     * not a client-side model of them. The timeline the UI draws is this data.
     */
    public record RunResponse(
            UUID runId,
            String scenarioId,
            String scenarioName,
            JsonNode config,
            String status,
            String outcomeNote,
            UUID endpointId,
            UUID eventId,
            UUID deliveryId,
            String simulatorReceivePath,
            DeliveryDtos.Response delivery,
            List<DeliveryDtos.AttemptResponse> attempts,
            SimulatorView simulator,
            /** Seconds until the next attempt, for the UI countdown. */
            Long secondsUntilNextAttempt,
            Instant createdAt,
            Instant completedAt) {}

    /** What the simulator itself has observed — the independent view of the same run. */
    public record SimulatorView(
            Integer requestsReceived, Integer failuresReturned, Integer successesReturned) {}

    public record RunSummary(
            UUID runId,
            String scenarioId,
            String scenarioName,
            String status,
            UUID deliveryId,
            Instant createdAt,
            Instant completedAt) {}
}

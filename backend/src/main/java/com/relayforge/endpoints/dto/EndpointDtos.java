package com.relayforge.endpoints.dto;

import com.relayforge.endpoints.WebhookEndpoint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for endpoint management. */
public final class EndpointDtos {

    private EndpointDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 2048) @Pattern(regexp = "^https?://.+", message = "must be an http or https URL")
                    String url,
            @Size(max = 500) String description,
            /** Empty or absent means "every event type". */
            Set<@Pattern(regexp = "^[a-zA-Z0-9_.*-]{1,120}$") String> subscribedEventTypes,
            @Min(1) @Max(25) Integer retryMaxAttempts,
            @Min(100) @Max(3_600_000) Long retryInitialDelayMs,
            @Min(100) @Max(86_400_000) Long retryMaxDelayMs,
            @Min(100) @Max(120_000) Integer retryTimeoutMs,
            @jakarta.validation.constraints.DecimalMin("0.0")
                    @jakarta.validation.constraints.DecimalMax("1.0")
                    Double retryJitterFactor,
            @Min(1) @Max(1000) Integer rateLimitPerSecond,
            /**
             * Optional caller-supplied signing secret, for migrating an existing
             * integration without changing the receiver's configuration.
             */
            @Size(min = 16, max = 256) String secret) {}

    public record UpdateRequest(
            @Size(max = 120) String name,
            @Size(max = 2048) @Pattern(regexp = "^https?://.+", message = "must be an http or https URL")
                    String url,
            @Size(max = 500) String description,
            Set<@Pattern(regexp = "^[a-zA-Z0-9_.*-]{1,120}$") String> subscribedEventTypes,
            @Min(1) @Max(25) Integer retryMaxAttempts,
            @Min(100) @Max(3_600_000) Long retryInitialDelayMs,
            @Min(100) @Max(86_400_000) Long retryMaxDelayMs,
            @Min(100) @Max(120_000) Integer retryTimeoutMs,
            @jakarta.validation.constraints.DecimalMin("0.0")
                    @jakarta.validation.constraints.DecimalMax("1.0")
                    Double retryJitterFactor,
            @Min(1) @Max(1000) Integer rateLimitPerSecond,
            Boolean active) {}

    /**
     * An endpoint as returned by the API.
     *
     * <p>Carries no secret material — not the ciphertext, not a hash, not a
     * prefix. The secret is returned exactly once, by create and by
     * rotate-secret, and never appears in a list or detail response.
     */
    public record Response(
            UUID id,
            String name,
            String url,
            String description,
            Set<String> subscribedEventTypes,
            boolean active,
            boolean paused,
            Instant pausedAt,
            Integer retryMaxAttempts,
            Long retryInitialDelayMs,
            Long retryMaxDelayMs,
            Integer retryTimeoutMs,
            Double retryJitterFactor,
            Integer rateLimitPerSecond,
            Instant secretRotatedAt,
            boolean hasPreviousSecret,
            Instant createdAt,
            Instant updatedAt,
            HealthSummary health) {

        public static Response from(WebhookEndpoint endpoint, HealthSummary health) {
            return new Response(
                    endpoint.getId(),
                    endpoint.getName(),
                    endpoint.getUrl(),
                    endpoint.getDescription(),
                    endpoint.getSubscribedEventTypes(),
                    endpoint.isActive(),
                    endpoint.getPausedAt() != null,
                    endpoint.getPausedAt(),
                    endpoint.getRetryMaxAttempts(),
                    endpoint.getRetryInitialDelayMs(),
                    endpoint.getRetryMaxDelayMs(),
                    endpoint.getRetryTimeoutMs(),
                    endpoint.getRetryJitterFactor(),
                    endpoint.getRateLimitPerSecond(),
                    endpoint.getSecretRotatedAt(),
                    endpoint.getPreviousEncryptedSecret() != null,
                    endpoint.getCreatedAt(),
                    endpoint.getUpdatedAt(),
                    health);
        }
    }

    /** Rolled-up delivery health for an endpoint over a recent window. */
    public record HealthSummary(
            long succeeded,
            long failed,
            long pending,
            double successRate,
            String state) {

        public static HealthSummary empty() {
            return new HealthSummary(0, 0, 0, 0d, "NO_DATA");
        }
    }

    /** The create / rotate response, which is the only place a secret is revealed. */
    public record SecretResponse(UUID endpointId, String secret, String warning) {}

    public record TestResult(
            boolean delivered,
            Integer responseStatus,
            Integer durationMs,
            String errorMessage,
            String errorClass,
            UUID eventId,
            UUID deliveryId) {}
}

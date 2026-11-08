package com.relayforge.retry;

import com.relayforge.config.RelayForgeProperties;
import com.relayforge.endpoints.WebhookEndpoint;
import java.time.Duration;
import java.util.Set;

/**
 * The effective retry rules for one delivery.
 *
 * <p>Resolved by layering an endpoint's overrides over the system defaults, so a
 * slow partner can be given a gentler schedule without every other endpoint
 * inheriting it, and an unset field means "use the default" rather than "use
 * zero".
 *
 * @param maxAttempts          total attempts, including the first
 * @param initialDelay         delay before attempt 2
 * @param maxDelay             ceiling the exponential growth is clamped to
 * @param jitterFactor         0 = deterministic, 1 = full jitter (see {@link BackoffCalculator})
 * @param timeout              per-attempt response budget
 * @param retryableStatusCodes non-5xx statuses that should still be retried
 * @param maxRetryAfter        upper bound honoured from a {@code Retry-After} header
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double jitterFactor,
        Duration timeout,
        Set<Integer> retryableStatusCodes,
        Duration maxRetryAfter) {

    /** The system-wide defaults. */
    public static RetryPolicy defaults(RelayForgeProperties properties) {
        RelayForgeProperties.Retry retry = properties.retry();
        return new RetryPolicy(
                retry.maxAttempts(),
                retry.initialDelay(),
                retry.maxDelay(),
                retry.jitterFactor(),
                properties.delivery().responseTimeout(),
                retry.retryableStatusCodes(),
                retry.maxRetryAfter());
    }

    /** Layers an endpoint's overrides over the defaults. */
    public static RetryPolicy forEndpoint(
            WebhookEndpoint endpoint, RelayForgeProperties properties) {
        RetryPolicy base = defaults(properties);
        if (endpoint == null) {
            return base;
        }
        return new RetryPolicy(
                endpoint.getRetryMaxAttempts() != null
                        ? endpoint.getRetryMaxAttempts()
                        : base.maxAttempts(),
                endpoint.getRetryInitialDelayMs() != null
                        ? Duration.ofMillis(endpoint.getRetryInitialDelayMs())
                        : base.initialDelay(),
                endpoint.getRetryMaxDelayMs() != null
                        ? Duration.ofMillis(endpoint.getRetryMaxDelayMs())
                        : base.maxDelay(),
                endpoint.getRetryJitterFactor() != null
                        ? endpoint.getRetryJitterFactor()
                        : base.jitterFactor(),
                endpoint.getRetryTimeoutMs() != null
                        ? Duration.ofMillis(endpoint.getRetryTimeoutMs())
                        : base.timeout(),
                base.retryableStatusCodes(),
                base.maxRetryAfter());
    }
}

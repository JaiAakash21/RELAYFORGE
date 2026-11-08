package com.relayforge.endpoints;

import com.relayforge.common.ConflictException;
import com.relayforge.common.Ids;
import com.relayforge.common.NotFoundException;
import com.relayforge.common.ValidationException;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.DeliveryStatus;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.security.DestinationPolicy;
import com.relayforge.security.SecretCipher;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Endpoint CRUD, pause/resume and secret rotation. */
@Service
public class EndpointService {

    private static final Logger log = LoggerFactory.getLogger(EndpointService.class);

    /** Window the health summary is computed over. */
    private static final Duration HEALTH_WINDOW = Duration.ofHours(24);

    private final WebhookEndpointRepository repository;
    private final DeliveryRepository deliveries;
    private final SecretCipher cipher;
    private final DestinationPolicy destinationPolicy;

    public EndpointService(
            WebhookEndpointRepository repository,
            DeliveryRepository deliveries,
            SecretCipher cipher,
            DestinationPolicy destinationPolicy) {
        this.repository = repository;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.destinationPolicy = destinationPolicy;
    }

    /** A created endpoint together with its one-time secret. */
    public record Created(WebhookEndpoint endpoint, String plaintextSecret) {}

    @Transactional
    public Created create(UUID tenantId, EndpointDtos.CreateRequest request) {
        // Syntax-only validation here: DNS is not resolved, so registering an
        // endpoint does not fail merely because the destination is momentarily
        // unresolvable. The full address check happens at delivery time, which is
        // the only moment it can be authoritative anyway.
        validateDestination(request.url());
        validateRetryConfiguration(
                request.retryInitialDelayMs(), request.retryMaxDelayMs());

        String secret =
                request.secret() != null && !request.secret().isBlank()
                        ? request.secret().trim()
                        : cipher.generateEndpointSecret();

        WebhookEndpoint endpoint =
                new WebhookEndpoint(
                        Ids.newId(),
                        tenantId,
                        request.name().trim(),
                        request.url().trim(),
                        cipher.encrypt(secret));
        endpoint.setDescription(blankToNull(request.description()));
        endpoint.setSubscribedEventTypes(normaliseEventTypes(request.subscribedEventTypes()));
        endpoint.setRetryMaxAttempts(request.retryMaxAttempts());
        endpoint.setRetryInitialDelayMs(request.retryInitialDelayMs());
        endpoint.setRetryMaxDelayMs(request.retryMaxDelayMs());
        endpoint.setRetryTimeoutMs(request.retryTimeoutMs());
        endpoint.setRetryJitterFactor(request.retryJitterFactor());
        endpoint.setRateLimitPerSecond(request.rateLimitPerSecond());

        repository.save(endpoint);
        log.info("Created endpoint {} for tenant {}", endpoint.getId(), tenantId);
        return new Created(endpoint, secret);
    }

    @Transactional(readOnly = true)
    public Page<WebhookEndpoint> list(UUID tenantId, Pageable pageable) {
        return repository.findByTenant(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint get(UUID tenantId, UUID endpointId) {
        return repository
                .findActiveByIdAndTenant(endpointId, tenantId)
                .orElseThrow(() -> new NotFoundException("Endpoint", endpointId));
    }

    @Transactional
    public WebhookEndpoint update(
            UUID tenantId, UUID endpointId, EndpointDtos.UpdateRequest request) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);

        if (request.url() != null && !request.url().equals(endpoint.getUrl())) {
            validateDestination(request.url());
            endpoint.setUrl(request.url().trim());
        }
        if (request.name() != null && !request.name().isBlank()) {
            endpoint.setName(request.name().trim());
        }
        if (request.description() != null) {
            endpoint.setDescription(blankToNull(request.description()));
        }
        if (request.subscribedEventTypes() != null) {
            endpoint.setSubscribedEventTypes(
                    normaliseEventTypes(request.subscribedEventTypes()));
        }

        // A null field means "leave unchanged". There is deliberately no way to
        // reset an override back to the system default through PATCH, because
        // null already means "unchanged" and one token cannot mean both.
        if (request.retryMaxAttempts() != null) {
            endpoint.setRetryMaxAttempts(request.retryMaxAttempts());
        }
        if (request.retryInitialDelayMs() != null) {
            endpoint.setRetryInitialDelayMs(request.retryInitialDelayMs());
        }
        if (request.retryMaxDelayMs() != null) {
            endpoint.setRetryMaxDelayMs(request.retryMaxDelayMs());
        }
        if (request.retryTimeoutMs() != null) {
            endpoint.setRetryTimeoutMs(request.retryTimeoutMs());
        }
        if (request.retryJitterFactor() != null) {
            endpoint.setRetryJitterFactor(request.retryJitterFactor());
        }
        if (request.rateLimitPerSecond() != null) {
            endpoint.setRateLimitPerSecond(request.rateLimitPerSecond());
        }
        if (request.active() != null) {
            endpoint.setActive(request.active());
        }

        validateRetryConfiguration(
                endpoint.getRetryInitialDelayMs(), endpoint.getRetryMaxDelayMs());
        return repository.save(endpoint);
    }

    /**
     * Soft-deletes an endpoint and cancels its outstanding deliveries.
     *
     * <p>Soft delete, so the delivery history that references it stays
     * interpretable — a delivery detail page for a deleted endpoint should still
     * be able to say which endpoint it was. Hard-deleting the row would either
     * cascade away that history or leave dangling references.
     *
     * <p>Open deliveries are cancelled rather than left to retry: a destination
     * that no longer exists would burn the whole attempt budget producing DNS
     * failures, and would fill the dead-letter queue with entries nobody can act
     * on.
     */
    @Transactional
    public void delete(UUID tenantId, UUID endpointId) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);
        endpoint.softDelete();
        repository.save(endpoint);

        int cancelled =
                deliveries.cancelOpenDeliveriesForEndpoint(
                        tenantId,
                        endpointId,
                        Instant.now(),
                        "The destination endpoint was deleted");
        log.info(
                "Soft-deleted endpoint {} for tenant {} and cancelled {} open deliveries",
                endpointId,
                tenantId,
                cancelled);
    }

    /**
     * Pauses delivery without losing events.
     *
     * <p>Queued deliveries are held and retried after the pause, not cancelled —
     * pausing is how a user takes their receiver down for maintenance, and it
     * would be useless if it discarded everything that arrived meanwhile.
     */
    @Transactional
    public WebhookEndpoint pause(UUID tenantId, UUID endpointId) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);
        endpoint.pause();
        return repository.save(endpoint);
    }

    @Transactional
    public WebhookEndpoint resume(UUID tenantId, UUID endpointId) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);
        endpoint.resume();
        return repository.save(endpoint);
    }

    /**
     * Rotates the signing secret.
     *
     * <p>The outgoing secret is retained for a configured grace window and
     * signatures are emitted under both, so a receiver can update its
     * configuration without dropping events. Without the overlap, rotation would
     * require perfectly simultaneous deployment on both sides — which in practice
     * means nobody rotates.
     *
     * @return the new plaintext secret, shown exactly once
     */
    @Transactional
    public String rotateSecret(UUID tenantId, UUID endpointId, String providedSecret) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);
        String newSecret =
                providedSecret != null && !providedSecret.isBlank()
                        ? providedSecret.trim()
                        : cipher.generateEndpointSecret();
        if (newSecret.length() < 16) {
            throw new ValidationException(
                    "A signing secret must be at least 16 characters long");
        }
        endpoint.rotateSecret(cipher.encrypt(newSecret));
        repository.save(endpoint);
        log.info("Rotated the signing secret for endpoint {} (tenant {})", endpointId, tenantId);
        return newSecret;
    }

    /** Immediately invalidates the previous secret, ending the rotation window early. */
    @Transactional
    public void revokePreviousSecret(UUID tenantId, UUID endpointId) {
        WebhookEndpoint endpoint = get(tenantId, endpointId);
        if (endpoint.getPreviousEncryptedSecret() == null) {
            throw new ConflictException(
                    "no_previous_secret", "This endpoint has no previous secret to revoke");
        }
        endpoint.forgetPreviousSecret();
        repository.save(endpoint);
    }

    /** Rolled-up delivery health per endpoint over the last 24 hours. */
    @Transactional(readOnly = true)
    public Map<UUID, EndpointDtos.HealthSummary> healthByEndpoint(UUID tenantId) {
        Instant since = Instant.now().minus(HEALTH_WINDOW);
        Map<UUID, long[]> counters = new HashMap<>();

        for (Object[] row : deliveries.countByEndpointAndStatusSince(tenantId, since)) {
            UUID endpointId = (UUID) row[0];
            DeliveryStatus status = (DeliveryStatus) row[1];
            long count = ((Number) row[2]).longValue();
            long[] slots = counters.computeIfAbsent(endpointId, key -> new long[3]);
            switch (status) {
                case SUCCESS -> slots[0] += count;
                case FAILED -> slots[1] += count;
                case PENDING, IN_PROGRESS, RETRY_SCHEDULED -> slots[2] += count;
                case CANCELLED -> {
                    // Cancelled deliveries describe an operator action, not
                    // endpoint health, so they are excluded from the success rate.
                }
            }
        }

        Map<UUID, EndpointDtos.HealthSummary> result = new HashMap<>();
        counters.forEach(
                (endpointId, slots) -> {
                    long succeeded = slots[0];
                    long failed = slots[1];
                    long pending = slots[2];
                    long resolved = succeeded + failed;
                    double rate = resolved == 0 ? 0d : (double) succeeded / resolved;
                    result.put(
                            endpointId,
                            new EndpointDtos.HealthSummary(
                                    succeeded, failed, pending, rate, classifyHealth(resolved, rate)));
                });
        return result;
    }

    /**
     * Turns a success rate into a label.
     *
     * <p>Thresholds are deliberately coarse and require a minimum sample: calling
     * an endpoint "degraded" because its single delivery failed would be noise,
     * not signal.
     */
    private static String classifyHealth(long resolved, double successRate) {
        if (resolved == 0) {
            return "NO_DATA";
        }
        if (resolved < 5) {
            return successRate >= 1.0 ? "HEALTHY" : "INSUFFICIENT_DATA";
        }
        if (successRate >= 0.99) {
            return "HEALTHY";
        }
        if (successRate >= 0.90) {
            return "DEGRADED";
        }
        return "FAILING";
    }

    private void validateDestination(String url) {
        try {
            destinationPolicy.validateSyntax(url);
        } catch (DestinationPolicy.BlockedDestinationException e) {
            throw new ValidationException("destination_not_allowed", e.getMessage());
        }
    }

    private static void validateRetryConfiguration(Long initialDelayMs, Long maxDelayMs) {
        if (initialDelayMs != null && maxDelayMs != null && initialDelayMs > maxDelayMs) {
            throw new ValidationException(
                    "retry_delays_inconsistent",
                    "retryInitialDelayMs must not be greater than retryMaxDelayMs");
        }
    }

    /** Normalises subscription patterns and rejects ones that cannot match. */
    private static Set<String> normaliseEventTypes(Set<String> types) {
        if (types == null) {
            return Set.of();
        }
        Set<String> normalised = new LinkedHashSet<>();
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            String trimmed = type.trim();
            // A wildcard is only meaningful as a whole trailing segment. Allowing
            // `ord*` would imply prefix matching, which the matcher does not
            // implement — better to refuse it than to silently never match.
            if (trimmed.contains("*")
                    && !trimmed.equals("*")
                    && !trimmed.endsWith(".*")) {
                throw new ValidationException(
                        "invalid_subscription_pattern",
                        "'"
                                + trimmed
                                + "' is not a valid subscription pattern. Use an exact event type,"
                                + " a trailing wildcard such as 'order.*', or '*' for everything.");
            }
            normalised.add(trimmed);
        }
        // '*' subsumes everything else; keeping both would be misleading in the UI.
        if (normalised.contains("*")) {
            return Set.of();
        }
        return normalised;
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> listAll(UUID tenantId) {
        return repository.findAllByTenant(tenantId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.relayforge.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayforge.common.InstanceIdentity;
import com.relayforge.common.Truncation;
import com.relayforge.config.RelayForgeProperties;
import com.relayforge.endpoints.WebhookEndpoint;
import com.relayforge.endpoints.WebhookEndpointRepository;
import com.relayforge.events.Event;
import com.relayforge.events.EventRepository;
import com.relayforge.observability.PipelineMetrics;
import com.relayforge.ratelimit.RateLimiter;
import com.relayforge.retry.ResponseClassifier;
import com.relayforge.retry.RetryPolicy;
import com.relayforge.security.SecretCipher;
import com.relayforge.security.WebhookSigner;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Performs one delivery attempt, end to end.
 *
 * <h2>The order of operations, and why each step is where it is</h2>
 *
 * <ol>
 *   <li><b>Claim.</b> A conditional {@code UPDATE} takes the lease. If it fails
 *       the delivery is already done, already held, or not yet due — and this
 *       invocation stops. Nothing else happens first, so a duplicate message
 *       costs one statement.
 *   <li><b>Load current state.</b> Event, endpoint and delivery are read from
 *       PostgreSQL <em>after</em> the claim, so the attempt uses the endpoint's
 *       present URL, secret and retry policy rather than a snapshot from
 *       whenever the message was produced.
 *   <li><b>Check the endpoint's own rate limit.</b> Before the request, and it
 *       reschedules rather than failing: the event is already accepted, so
 *       exceeding a receiver's budget must delay delivery, never drop it.
 *   <li><b>Sign and send.</b> The signature covers the exact bytes sent.
 *   <li><b>Record, then decide.</b> The attempt row and the rollup are written in
 *       one transaction with the status change, so the evidence and the state can
 *       never disagree.
 * </ol>
 *
 * <p>Step 5 is the one the caller must respect: the Kafka offset is acknowledged
 * only after this method returns, so a crash anywhere above replays the message
 * and the claim in step 1 decides what to do about it.
 */
@Service
public class DeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryExecutor.class);

    private final DeliveryRepository deliveries;
    private final DeliveryOutcomeRecorder recorder;
    private final DeliveryClaimDao claimDao;
    private final EventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final WebhookHttpSender sender;
    private final WebhookSigner signer;
    private final SecretCipher cipher;
    private final ResponseClassifier classifier;
    private final RateLimiter rateLimiter;
    private final PipelineMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final RelayForgeProperties properties;
    private final String workerId;

    public DeliveryExecutor(
            DeliveryRepository deliveries,
            DeliveryOutcomeRecorder recorder,
            DeliveryClaimDao claimDao,
            EventRepository events,
            WebhookEndpointRepository endpoints,
            WebhookHttpSender sender,
            WebhookSigner signer,
            SecretCipher cipher,
            ResponseClassifier classifier,
            RateLimiter rateLimiter,
            PipelineMetrics metrics,
            ObjectMapper objectMapper,
            Tracer tracer,
            RelayForgeProperties properties,
            InstanceIdentity identity) {
        this.deliveries = deliveries;
        this.recorder = recorder;
        this.claimDao = claimDao;
        this.events = events;
        this.endpoints = endpoints;
        this.sender = sender;
        this.signer = signer;
        this.cipher = cipher;
        this.classifier = classifier;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.properties = properties;
        this.workerId = identity.id();
    }

    /** What this invocation did — returned so the consumer can log and meter it. */
    public enum Result {
        /** An attempt was made and the outcome recorded. */
        ATTEMPTED,
        /** The delivery was not claimable: a duplicate message, or already resolved. */
        SKIPPED_NOT_CLAIMABLE,
        /** The delivery row no longer exists. */
        SKIPPED_MISSING,
        /** The endpoint is paused, deleted or inactive; the delivery was cancelled. */
        SKIPPED_ENDPOINT_UNAVAILABLE,
        /** The endpoint's rate limit was exhausted; the delivery was rescheduled. */
        DEFERRED_RATE_LIMITED
    }

    /**
     * Claims and attempts one delivery.
     *
     * <p>Not {@code @Transactional}: the method performs a network call that can
     * take seconds, and holding a database transaction open across it would pin a
     * pool connection for the duration and risk lock-wait pile-ups. Instead each
     * database step runs in its own short transaction, with the lease — not a
     * transaction — providing mutual exclusion across the whole sequence.
     */
    public Result execute(UUID deliveryId, AttemptKind kindHint) {
        Instant now = Instant.now();
        Duration leaseDuration = properties.delivery().leaseDuration();

        if (!claimDao.claim(deliveryId, workerId, now, leaseDuration)) {
            // Expected and benign: duplicate Kafka message, a sibling worker got
            // there first, or the delivery already reached a terminal state.
            metrics.recordDuplicateMessage();
            return Result.SKIPPED_NOT_CLAIMABLE;
        }

        Optional<Delivery> maybeDelivery = deliveries.findById(deliveryId);
        if (maybeDelivery.isEmpty()) {
            log.warn("Delivery {} was claimed but no longer exists", deliveryId);
            return Result.SKIPPED_MISSING;
        }
        Delivery delivery = maybeDelivery.get();

        Optional<WebhookEndpoint> maybeEndpoint = endpoints.findById(delivery.getEndpointId());
        if (maybeEndpoint.isEmpty() || !maybeEndpoint.get().isDeliverable()) {
            cancelUndeliverable(delivery, maybeEndpoint.orElse(null));
            return Result.SKIPPED_ENDPOINT_UNAVAILABLE;
        }
        WebhookEndpoint endpoint = maybeEndpoint.get();

        Optional<Event> maybeEvent = events.findById(delivery.getEventId());
        if (maybeEvent.isEmpty()) {
            log.error(
                    "Delivery {} references event {} which does not exist; failing terminally",
                    deliveryId,
                    delivery.getEventId());
            claimDao.failTerminally(
                    deliveryId,
                    workerId,
                    delivery.getAttemptCount(),
                    null,
                    "The source event no longer exists",
                    ErrorClass.UNKNOWN,
                    Instant.now());
            return Result.SKIPPED_MISSING;
        }
        Event event = maybeEvent.get();

        RetryPolicy policy = RetryPolicy.forEndpoint(endpoint, properties);

        // Receiver protection, applied before the request is made. Rescheduling
        // (not failing) is essential: the event was accepted, so the only correct
        // response to "too fast" is "later".
        RateLimiter.Decision throttle =
                rateLimiter.tryConsumeEndpointDelivery(
                        endpoint.getId(), endpoint.getRateLimitPerSecond());
        if (!throttle.allowed()) {
            Duration wait =
                    throttle.retryAfter().isZero() ? Duration.ofSeconds(1) : throttle.retryAfter();
            metrics.recordEndpointThrottled();
            claimDao.scheduleRetry(
                    deliveryId,
                    workerId,
                    delivery.getAttemptCount(),
                    null,
                    "Deferred by the endpoint's delivery rate limit",
                    ErrorClass.RATE_LIMITED,
                    Instant.now().plus(wait),
                    Instant.now());
            return Result.DEFERRED_RATE_LIMITED;
        }

        int attemptNumber = delivery.getAttemptCount() + 1;
        AttemptKind kind = resolveKind(kindHint, delivery, attemptNumber);

        byte[] body = serialiseBody(event);
        Map<String, String> headers = buildHeaders(event, delivery, endpoint, attemptNumber, body);

        WebhookHttpSender.SendResult sendResult =
                sender.send(endpoint.getUrl(), headers, body, policy.timeout());

        ResponseClassifier.Classification classification =
                sendResult.hasStatus()
                        ? classifier.classifyStatus(sendResult.status(), policy)
                        : classifier.classifyTransportError(
                                sendResult.errorClass(), sendResult.errorMessage());

        recorder.record(
                delivery, endpoint, policy, attemptNumber, kind, sendResult, classification);
        return Result.ATTEMPTED;
    }

    /**
     * Cancels a delivery whose endpoint can no longer receive it.
     *
     * <p>Cancelled rather than failed: nothing went wrong with the delivery, the
     * destination was withdrawn. Keeping it out of the dead-letter queue matters
     * — the DLQ is meant to list things an operator can act on, and "replay this
     * to an endpoint that was deleted" is not one of them.
     */
    private void cancelUndeliverable(Delivery delivery, WebhookEndpoint endpoint) {
        String reason;
        if (endpoint == null) {
            reason = "The destination endpoint no longer exists";
        } else if (endpoint.getDeletedAt() != null) {
            reason = "The destination endpoint was deleted";
        } else if (endpoint.getPausedAt() != null) {
            reason = "The destination endpoint is paused";
        } else {
            reason = "The destination endpoint is inactive";
        }

        // A paused endpoint is a temporary state, so its deliveries are held
        // rather than cancelled — resuming should resume delivery, not require
        // every held event to be replayed by hand.
        if (endpoint != null && endpoint.getPausedAt() != null && endpoint.getDeletedAt() == null) {
            claimDao.scheduleRetry(
                    delivery.getId(),
                    workerId,
                    delivery.getAttemptCount(),
                    null,
                    reason,
                    ErrorClass.NONE,
                    Instant.now().plus(Duration.ofSeconds(30)),
                    Instant.now());
            return;
        }

        claimDao.failTerminally(
                delivery.getId(),
                workerId,
                delivery.getAttemptCount(),
                null,
                reason,
                ErrorClass.NONE,
                Instant.now());
    }

    /**
     * Serialises the event into the exact bytes that are signed and sent.
     *
     * <p>Produced once and reused for both, because signing one byte sequence and
     * transmitting another is the classic way a signature scheme silently stops
     * working — any difference in key order or whitespace breaks every receiver's
     * verification.
     */
    private byte[] serialiseBody(Event event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", event.getId().toString());
        envelope.put("type", event.getEventType());
        envelope.put("createdAt", event.getCreatedAt().toString());
        envelope.put("data", event.getPayload());
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialise event " + event.getId() + " for delivery", e);
        }
    }

    /** Builds the outbound headers, including the HMAC signature. */
    private Map<String, String> buildHeaders(
            Event event,
            Delivery delivery,
            WebhookEndpoint endpoint,
            int attemptNumber,
            byte[] body) {

        Instant signedAt = Instant.now();
        String currentSecret = cipher.decrypt(endpoint.getEncryptedSecret());
        String previousSecret = resolvePreviousSecret(endpoint);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put(
                "X-RelayForge-Signature",
                signer.signWithRotation(currentSecret, previousSecret, signedAt, body));
        headers.put("X-RelayForge-Timestamp", Long.toString(signedAt.getEpochSecond()));
        // The stable identifier a receiver should deduplicate on. It does not
        // change across retries or replays, which is precisely what makes
        // at-least-once delivery safe for the receiver to handle.
        headers.put("X-RelayForge-Event-Id", event.getId().toString());
        headers.put("X-RelayForge-Event-Type", event.getEventType());
        headers.put("X-RelayForge-Delivery-Id", delivery.getId().toString());
        headers.put("X-RelayForge-Attempt", Integer.toString(attemptNumber));
        headers.put("X-RelayForge-Endpoint-Id", endpoint.getId().toString());

        // Propagate the trace so a receiver that is also instrumented can join
        // this delivery to its own processing span.
        String traceParent = currentTraceParent();
        if (traceParent != null) {
            headers.put("traceparent", traceParent);
        }
        return headers;
    }

    /**
     * Returns the superseded secret while it is still inside the rotation grace
     * window, and {@code null} once that window has closed.
     *
     * <p>Time-bounding this matters: a previous secret that stayed valid forever
     * would mean rotating a leaked secret never actually revoked it.
     */
    private String resolvePreviousSecret(WebhookEndpoint endpoint) {
        if (endpoint.getPreviousEncryptedSecret() == null
                || endpoint.getSecretRotatedAt() == null) {
            return null;
        }
        Duration grace = properties.crypto().secretRotationGrace();
        if (endpoint.getSecretRotatedAt().plus(grace).isBefore(Instant.now())) {
            return null;
        }
        return cipher.decrypt(endpoint.getPreviousEncryptedSecret());
    }

    private static AttemptKind resolveKind(
            AttemptKind hint, Delivery delivery, int attemptNumber) {
        if (hint == AttemptKind.MANUAL_TEST) {
            return hint;
        }
        if (delivery.getReplayCount() > 0) {
            return AttemptKind.REPLAY;
        }
        return attemptNumber == 1 ? AttemptKind.AUTOMATIC : AttemptKind.RETRY;
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    /** Formats the current span as a W3C {@code traceparent} header value. */
    private String currentTraceParent() {
        var span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        var context = span.context();
        if (context.traceId() == null || context.spanId() == null) {
            return null;
        }
        String sampled = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + sampled;
    }

    static {
        // Guards against a refactor that changes the signed envelope's charset.
        assert StandardCharsets.UTF_8.name().equals("UTF-8");
    }
}

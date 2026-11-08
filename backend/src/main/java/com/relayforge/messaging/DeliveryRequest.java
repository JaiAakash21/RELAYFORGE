package com.relayforge.messaging;

import java.util.UUID;

/**
 * The Kafka message that asks a worker to attempt a delivery.
 *
 * <h2>Why it carries identifiers, not the payload</h2>
 *
 * The message is deliberately a pointer, not the data. The worker re-reads the
 * event, the endpoint and the delivery row from PostgreSQL before acting.
 *
 * <ul>
 *   <li><b>PostgreSQL stays the source of truth.</b> If an endpoint was paused or
 *       its secret rotated between enqueue and processing, the worker acts on the
 *       current state. A self-contained message would act on a stale snapshot —
 *       and would, for instance, keep signing with a secret the user had already
 *       rotated away from.
 *   <li><b>Duplicates are harmless.</b> Processing is gated on a conditional
 *       {@code UPDATE} against the live row, so a replayed message is a no-op
 *       rather than a second delivery.
 *   <li><b>No secrets on the broker.</b> Kafka retains messages; the signing
 *       secret and the full payload stay in the database behind access control.
 * </ul>
 *
 * The cost is one indexed read per attempt. That is far cheaper than the
 * correctness and security problems the alternative creates.
 *
 * @param deliveryId  the delivery to attempt
 * @param tenantId    carried for log/trace correlation without an extra lookup
 * @param eventId     carried for the same reason
 * @param endpointId  also the Kafka partition key
 * @param attemptHint the attempt count when enqueued — diagnostic only, never
 *                    authoritative; the worker always trusts the database
 * @param enqueuedAtEpochMs when the message was produced, used to measure lag
 */
public record DeliveryRequest(
        UUID deliveryId,
        UUID tenantId,
        UUID eventId,
        UUID endpointId,
        int attemptHint,
        long enqueuedAtEpochMs) {}

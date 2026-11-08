package com.relayforge.outbox;

/**
 * Lifecycle of an outbox row.
 *
 * <pre>
 *   PENDING ──claim──► CLAIMED ──broker ack──► PUBLISHED
 *      ▲                  │
 *      └── claim expiry ──┘        (publisher crashed mid-flight)
 *
 *   CLAIMED ──too many failed publishes──► DEAD  (needs operator attention)
 * </pre>
 */
public enum OutboxStatus {
    /** Written with its aggregate, not yet relayed. */
    PENDING,

    /** A publisher instance has taken it and is relaying it right now. */
    CLAIMED,

    /** The broker acknowledged the record. Terminal and safe. */
    PUBLISHED,

    /** Repeated publish failures. Parked for an operator rather than retried forever. */
    DEAD
}

package com.relayforge.delivery;

/**
 * The delivery state machine.
 *
 * <pre>
 *                      ┌──────────────── replay ──────────────┐
 *                      │                                      │
 *                      v                                      │
 *   (ingest) ──► PENDING ──claim──► IN_PROGRESS ──2xx──► SUCCESS
 *                   ▲                   │  │
 *                   │                   │  └── 4xx (non-retryable) ──► FAILED
 *                   │                   │
 *                   │        retryable & attempts left
 *                   │                   │
 *                   │                   v
 *                   └──dispatch── RETRY_SCHEDULED
 *                                       │
 *                            attempts exhausted
 *                                       │
 *                                       v
 *                                    FAILED  ──replay──► PENDING
 *
 *   any non-terminal state ──operator cancel──► CANCELLED
 * </pre>
 *
 * <h2>Why FAILED is the dead-letter state</h2>
 *
 * There is no separate {@code DEAD_LETTERED} status. A delivery that has
 * exhausted its attempts <em>is</em> the dead letter: the dead-letter queue is a
 * view over {@code status = 'FAILED'}, not a second copy of the row. Keeping one
 * row means replay cannot desynchronise two representations of the same
 * delivery, and the attempt history stays attached to the thing it describes.
 *
 * <h2>Lease-expiry recovery</h2>
 *
 * {@code IN_PROGRESS} is the only state that can be entered without an operator
 * or a schedule, and the only one that a worker crash can strand. It is
 * therefore the only state guarded by a lease: when {@code lease_until} passes,
 * the reconciler moves the row back to {@code RETRY_SCHEDULED}.
 */
public enum DeliveryStatus {
    /** Created and waiting to be picked up for its first attempt. */
    PENDING,

    /** Leased by a worker. An HTTP request is in flight, or the worker died. */
    IN_PROGRESS,

    /** A retryable failure occurred; {@code next_attempt_at} holds the due time. */
    RETRY_SCHEDULED,

    /** A 2xx response was received. Terminal. */
    SUCCESS,

    /** Attempts exhausted, or a non-retryable response. Terminal; replayable. */
    FAILED,

    /** Stopped by an operator, or the endpoint was deleted. Terminal. */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    /** True when a worker may legitimately claim a delivery in this state. */
    public boolean isClaimable() {
        return this == PENDING || this == RETRY_SCHEDULED;
    }

    /** Only exhausted deliveries may be replayed. */
    public boolean isReplayable() {
        return this == FAILED || this == CANCELLED;
    }
}

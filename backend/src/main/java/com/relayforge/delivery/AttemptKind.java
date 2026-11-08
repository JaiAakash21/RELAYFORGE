package com.relayforge.delivery;

/** Why an attempt happened — distinguishes automatic work from operator action. */
public enum AttemptKind {
    /** The first automatic attempt. */
    AUTOMATIC,

    /** An automatic attempt after a scheduled backoff. */
    RETRY,

    /** An attempt created by an explicit replay of a dead-lettered delivery. */
    REPLAY,

    /** A one-off connectivity test from the endpoint screen. */
    MANUAL_TEST
}

package com.relayforge.delivery;

/** How a single HTTP attempt ended, after classification. */
public enum AttemptOutcome {
    /** 2xx. */
    SUCCESS,

    /** Timeout, connection error, 408/425/429/5xx — worth trying again. */
    RETRYABLE_FAILURE,

    /** 4xx (other than the retryable few), or a redirect. Retrying cannot help. */
    TERMINAL_FAILURE
}

package com.relayforge.delivery;

/**
 * A coarse, bounded classification of why an attempt failed.
 *
 * <p>Bounded on purpose: this value becomes a Prometheus label, and an unbounded
 * label (such as a raw exception message) would multiply the time-series
 * cardinality by the number of distinct failures a third party can invent.
 */
public enum ErrorClass {
    NONE,
    /** DNS resolution failed or returned nothing usable. */
    DNS_FAILURE,
    /** TCP connect failed or was refused. */
    CONNECTION_REFUSED,
    /** Connect phase exceeded its budget. */
    CONNECT_TIMEOUT,
    /** Response did not arrive within the read budget. */
    READ_TIMEOUT,
    /** TLS handshake or certificate validation failed. */
    TLS_FAILURE,
    /** Destination rejected by the SSRF policy. */
    BLOCKED_DESTINATION,
    /** Receiver asked us to slow down (429, or 503 with Retry-After). */
    RATE_LIMITED,
    /** Receiver returned 5xx. */
    SERVER_ERROR,
    /** Receiver returned a non-retryable 4xx. */
    CLIENT_ERROR,
    /** Receiver answered with a redirect, which we do not follow. */
    REDIRECT_NOT_FOLLOWED,
    /** Response exceeded the size we are willing to read. */
    RESPONSE_TOO_LARGE,
    /**
     * The receiver closed the connection before completing its response.
     *
     * <p>Distinguished from {@link #CONNECTION_REFUSED}: the connection was
     * established and the request was almost certainly delivered, so the
     * receiver may well have acted on it. A user debugging this needs to know
     * that, because it means a retry can produce a duplicate on their side.
     */
    RESPONSE_INCOMPLETE,
    /** Anything else — kept as a single bucket rather than a free-text label. */
    UNKNOWN
}

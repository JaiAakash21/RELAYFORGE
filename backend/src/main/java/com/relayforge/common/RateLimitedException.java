package com.relayforge.common;

import org.springframework.http.HttpStatus;

/** The caller exceeded a configured rate limit. */
public class RateLimitedException extends RelayForgeException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

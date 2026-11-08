package com.relayforge.common;

import org.springframework.http.HttpStatus;

/**
 * Base class for every error this application raises deliberately.
 *
 * <p>Carrying the HTTP status and a stable machine-readable {@code code} on the
 * exception means the web layer never has to guess how to translate a domain
 * failure, and clients can branch on {@code code} instead of parsing prose.
 */
public class RelayForgeException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public RelayForgeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public RelayForgeException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

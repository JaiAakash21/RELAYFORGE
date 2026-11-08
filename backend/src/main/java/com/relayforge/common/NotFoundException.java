package com.relayforge.common;

import org.springframework.http.HttpStatus;

/**
 * Raised when a resource does not exist <em>for the calling tenant</em>.
 *
 * <p>Deliberately indistinguishable from "exists but belongs to someone else":
 * returning 404 rather than 403 for a cross-tenant reference stops the API from
 * being used as an existence oracle for other tenants' identifiers.
 */
public class NotFoundException extends RelayForgeException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "not_found", resource + " " + id + " was not found");
    }

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "not_found", message);
    }
}

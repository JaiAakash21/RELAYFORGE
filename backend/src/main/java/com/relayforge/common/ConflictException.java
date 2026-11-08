package com.relayforge.common;

import org.springframework.http.HttpStatus;

/** The request conflicts with the current state of the resource. */
public class ConflictException extends RelayForgeException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "conflict", message);
    }

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}

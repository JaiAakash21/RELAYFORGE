package com.relayforge.common;

import org.springframework.http.HttpStatus;

/** A request was syntactically valid but semantically unacceptable. */
public class ValidationException extends RelayForgeException {

    public ValidationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", message);
    }

    public ValidationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}

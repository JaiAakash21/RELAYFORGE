package com.relayforge.common;

import org.springframework.http.HttpStatus;

/** The caller is authenticated but not permitted to perform this action. */
public class ForbiddenException extends RelayForgeException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}

package com.relayforge.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error shape every endpoint returns, so a client needs exactly one
 * error parser.
 *
 * @param code       stable machine-readable identifier, e.g. {@code not_found}
 * @param message    human-readable summary, safe to show to a developer
 * @param details    optional field-level problems, keyed by field path
 * @param traceId    current trace identifier, so a user-visible error can be
 *                   correlated with server logs and traces without guesswork
 * @param timestamp  when the error was produced
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String message,
        Map<String, List<String>> details,
        String traceId,
        Instant timestamp) {

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, Map.of(), traceId, Instant.now());
    }

    public static ApiError of(
            String code, String message, Map<String, List<String>> details, String traceId) {
        return new ApiError(code, message, details, traceId, Instant.now());
    }
}

package com.relayforge.common;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Translates every exception into the single {@link ApiError} shape.
 *
 * <h2>Two rules this handler enforces</h2>
 *
 * <ol>
 *   <li><b>No internal detail escapes.</b> An unexpected exception becomes a
 *       generic 500 with a trace id. The stack trace goes to the logs, where it
 *       belongs — returning it would leak class names, SQL fragments and library
 *       versions to anyone who can provoke an error.
 *   <li><b>Every error carries a trace id.</b> That single string is what turns
 *       "it failed" into a searchable incident, and it is the one piece of
 *       internal information that is safe and useful to expose.
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Errors this application raises deliberately already know their status. */
    @ExceptionHandler(RelayForgeException.class)
    public ResponseEntity<ApiError> handleRelayForge(RelayForgeException e) {
        if (e.status().is5xxServerError()) {
            log.error("Server-side domain failure: {}", e.getMessage(), e);
        } else {
            log.debug("Client error [{}]: {}", e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.status())
                .body(ApiError.of(e.code(), e.getMessage(), traceId()));
    }

    /** Rate limiting adds {@code Retry-After} so a well-behaved client can comply. */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiError> handleRateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(ApiError.of(e.code(), e.getMessage(), traceId()));
    }

    /** Bean-validation failures become per-field details the UI can place inline. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, List<String>> details = new LinkedHashMap<>();
        e.getBindingResult()
                .getFieldErrors()
                .forEach(
                        error ->
                                details
                                        .computeIfAbsent(error.getField(), key -> new ArrayList<>())
                                        .add(
                                                error.getDefaultMessage() == null
                                                        ? "is invalid"
                                                        : error.getDefaultMessage()));
        e.getBindingResult()
                .getGlobalErrors()
                .forEach(
                        error ->
                                details
                                        .computeIfAbsent("_", key -> new ArrayList<>())
                                        .add(
                                                error.getDefaultMessage() == null
                                                        ? "is invalid"
                                                        : error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(
                        ApiError.of(
                                "validation_failed",
                                "The request body failed validation",
                                details,
                                traceId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException e) {
        Map<String, List<String>> details = new LinkedHashMap<>();
        e.getConstraintViolations()
                .forEach(
                        violation ->
                                details
                                        .computeIfAbsent(
                                                violation.getPropertyPath().toString(),
                                                key -> new ArrayList<>())
                                        .add(violation.getMessage()));
        return ResponseEntity.badRequest()
                .body(
                        ApiError.of(
                                "validation_failed",
                                "The request failed validation",
                                details,
                                traceId()));
    }

    /**
     * Malformed JSON.
     *
     * <p>The parser's own message is not returned: it quotes the offending input
     * back, which can reflect an attacker's payload into a response, and it names
     * internal class paths.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body", e);
        return ResponseEntity.badRequest()
                .body(
                        ApiError.of(
                                "malformed_request",
                                "The request body could not be parsed as JSON",
                                traceId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(
                        ApiError.of(
                                "missing_parameter",
                                "Required parameter '" + e.getParameterName() + "' is missing",
                                traceId()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(
                        ApiError.of(
                                "invalid_parameter",
                                "Parameter '" + e.getName() + "' has an invalid value",
                                traceId()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(
                        ApiError.of(
                                "payload_too_large",
                                "The request body exceeds the maximum accepted size",
                                traceId()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "No such endpoint", traceId()));
    }

    /**
     * Optimistic-locking failure.
     *
     * <p>Surfaced as 409 rather than 500 because it is a genuine conflict: two
     * requests tried to change the same row, and retrying is the correct client
     * response.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException e) {
        log.debug("Optimistic locking conflict", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        ApiError.of(
                                "concurrent_modification",
                                "This resource was modified concurrently; reload and try again",
                                traceId()));
    }

    /**
     * Anything unanticipated.
     *
     * <p>Logged in full with the trace id, answered with nothing but that id.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = traceId();
        log.error(
                "Unhandled exception while serving {} {} (trace {})",
                request.getMethod(),
                request.getRequestURI(),
                traceId,
                e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        ApiError.of(
                                "internal_error",
                                "An unexpected error occurred. Quote the trace id when reporting"
                                        + " this.",
                                traceId));
    }

    private String traceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}

package com.relayforge.delivery;

import com.relayforge.common.Truncation;
import com.relayforge.config.RelayForgeProperties;
import com.relayforge.security.DestinationPolicy;
import com.relayforge.security.PinnedDnsResolver;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Performs one outbound webhook request and reports exactly what happened.
 *
 * <p>Everything this class does is in service of one invariant: <em>a receiver
 * must never be able to harm the platform</em>. That means bounded time, bounded
 * memory, a validated destination, and no silent library behaviour that would
 * make the recorded attempt history inaccurate.
 */
@Component
public class WebhookHttpSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookHttpSender.class);

    private final CloseableHttpClient httpClient;
    private final PinnedDnsResolver pinnedDnsResolver;
    private final DestinationPolicy destinationPolicy;
    private final RelayForgeProperties.Delivery config;

    public WebhookHttpSender(
            CloseableHttpClient httpClient,
            PinnedDnsResolver pinnedDnsResolver,
            DestinationPolicy destinationPolicy,
            RelayForgeProperties properties) {
        this.httpClient = httpClient;
        this.pinnedDnsResolver = pinnedDnsResolver;
        this.destinationPolicy = destinationPolicy;
        this.config = properties.delivery();
    }

    /** The outcome of one HTTP attempt. */
    public record SendResult(
            Integer status,
            String bodyExcerpt,
            String headersExcerpt,
            Integer retryAfterSeconds,
            ErrorClass errorClass,
            String errorMessage,
            int durationMs,
            Instant startedAt,
            Instant completedAt,
            int requestBytes) {

        public boolean hasStatus() {
            return status != null;
        }
    }

    /**
     * Sends a signed webhook.
     *
     * @param url     the destination, re-validated here rather than trusted from
     *                the time the endpoint was registered — a DNS record may have
     *                changed in between, and this is the last point at which we
     *                can refuse
     * @param headers headers to send, including the signature
     * @param body    the exact bytes that were signed
     * @param timeout per-attempt response budget
     */
    public SendResult send(String url, Map<String, String> headers, byte[] body, Duration timeout) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();

        if (body != null && body.length > config.maxRequestBytes()) {
            return failure(
                    ErrorClass.RESPONSE_TOO_LARGE,
                    "Request body of "
                            + body.length
                            + " bytes exceeds the configured maximum of "
                            + config.maxRequestBytes(),
                    startedAt,
                    startNanos,
                    body.length);
        }

        DestinationPolicy.Approved approved;
        try {
            approved = destinationPolicy.validate(url);
        } catch (DestinationPolicy.BlockedDestinationException e) {
            // Blocked destinations are recorded as attempts so the user can see
            // why nothing was delivered, rather than the delivery silently
            // stalling with no explanation.
            ErrorClass errorClass =
                    e.reasonCode().startsWith("dns")
                            ? ErrorClass.DNS_FAILURE
                            : ErrorClass.BLOCKED_DESTINATION;
            return failure(errorClass, e.getMessage(), startedAt, startNanos, length(body));
        }

        // Pin the validated addresses for the duration of this request so the
        // client cannot resolve the hostname again and reach somewhere else.
        pinnedDnsResolver.pin(approved.host(), approved.addresses());
        try {
            HttpPost request = new HttpPost(approved.uri());
            headers.forEach(request::setHeader);
            request.setEntity(
                    new ByteArrayEntity(
                            body == null ? new byte[0] : body, ContentType.APPLICATION_JSON));
            request.setConfig(
                    RequestConfig.copy(RequestConfig.DEFAULT)
                            .setConnectionRequestTimeout(
                                    Timeout.ofMilliseconds(config.connectTimeout().toMillis()))
                            .setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis()))
                            .setRedirectsEnabled(false)
                            .build());

            return httpClient.execute(
                    request,
                    response -> {
                        int status = response.getCode();
                        String bodyExcerpt = readBoundedBody(response.getEntity());
                        String headersExcerpt = summariseHeaders(response.getHeaders());
                        Integer retryAfter = parseRetryAfter(response.getFirstHeader("Retry-After"));
                        int durationMs = elapsedMillis(startNanos);
                        return new SendResult(
                                status,
                                bodyExcerpt,
                                headersExcerpt,
                                retryAfter,
                                null,
                                null,
                                durationMs,
                                startedAt,
                                Instant.now(),
                                length(body));
                    });
        } catch (ConnectTimeoutException e) {
            return failure(
                    ErrorClass.CONNECT_TIMEOUT,
                    "Connection to the endpoint timed out after "
                            + config.connectTimeout().toMillis()
                            + " ms",
                    startedAt,
                    startNanos,
                    length(body));
        } catch (SocketTimeoutException e) {
            return failure(
                    ErrorClass.READ_TIMEOUT,
                    "The endpoint did not respond within " + timeout.toMillis() + " ms",
                    startedAt,
                    startNanos,
                    length(body));
        } catch (HttpHostConnectException e) {
            return failure(
                    ErrorClass.CONNECTION_REFUSED,
                    "Could not establish a connection to the endpoint",
                    startedAt,
                    startNanos,
                    length(body));
        } catch (UnknownHostException e) {
            return failure(
                    ErrorClass.DNS_FAILURE,
                    "The endpoint hostname could not be resolved",
                    startedAt,
                    startNanos,
                    length(body));
        } catch (SSLException e) {
            return failure(
                    ErrorClass.TLS_FAILURE,
                    "TLS handshake with the endpoint failed: "
                            + Truncation.truncate(e.getMessage(), 200),
                    startedAt,
                    startNanos,
                    length(body));
        } catch (org.apache.hc.core5.http.ConnectionClosedException
                | org.apache.hc.core5.http.MalformedChunkCodingException e) {
            // The connection was established and the request was sent, but the
            // response was cut short. Retryable, and flagged distinctly because
            // the receiver may already have processed the request.
            return failure(
                    ErrorClass.RESPONSE_INCOMPLETE,
                    "The endpoint closed the connection before completing its response; the"
                            + " request may already have been processed",
                    startedAt,
                    startNanos,
                    length(body));
        } catch (ResponseTooLargeException e) {
            return failure(
                    ErrorClass.RESPONSE_TOO_LARGE, e.getMessage(), startedAt, startNanos, length(body));
        } catch (IOException | RuntimeException e) {
            // The message is truncated and the exception type is not surfaced to
            // the tenant verbatim: a stack-trace-shaped error message in a UI is
            // both useless and a small information leak.
            log.debug("Webhook delivery to {} failed", approved.host(), e);
            return failure(
                    ErrorClass.UNKNOWN,
                    "Delivery failed: " + Truncation.truncate(describe(e), 200),
                    startedAt,
                    startNanos,
                    length(body));
        } finally {
            pinnedDnsResolver.unpin(approved.host());
        }
    }

    /** Signals that a receiver's response exceeded the readable limit. */
    static class ResponseTooLargeException extends RuntimeException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Reads at most {@code maxResponseBytes} of the response body.
     *
     * <p>Reads incrementally rather than consuming the entity and truncating
     * afterwards. A receiver that streams an endless response — deliberately or
     * through a bug — would otherwise exhaust the worker's heap before anything
     * had a chance to truncate it. Stopping at the limit bounds memory
     * regardless of what the far side sends.
     */
    private String readBoundedBody(org.apache.hc.core5.http.HttpEntity entity) {
        if (entity == null) {
            return null;
        }
        int limit = Math.min(config.maxResponseBytes(), Truncation.RESPONSE_BODY_EXCERPT_LIMIT * 4);
        try (InputStream in = entity.getContent()) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[limit];
            int total = 0;
            while (total < limit) {
                int read = in.read(buffer, total, limit - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            String text = new String(buffer, 0, total, StandardCharsets.UTF_8);
            return Truncation.responseBody(text);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Records a small, fixed set of response headers.
     *
     * <p>An allowlist rather than everything: response headers are attacker-
     * influenced, can carry a receiver's own credentials (a {@code Set-Cookie},
     * say), and are displayed back in the UI. Only headers that help debug a
     * delivery are kept.
     */
    private static String summariseHeaders(Header[] headers) {
        if (headers == null || headers.length == 0) {
            return null;
        }
        java.util.Set<String> interesting =
                java.util.Set.of(
                        "content-type",
                        "content-length",
                        "retry-after",
                        "x-request-id",
                        "x-correlation-id",
                        "server",
                        "date",
                        "ratelimit-remaining",
                        "x-ratelimit-remaining");
        Map<String, String> kept = new LinkedHashMap<>();
        for (Header header : headers) {
            String name = header.getName().toLowerCase(java.util.Locale.ROOT);
            if (interesting.contains(name) && kept.size() < 12) {
                kept.put(name, Truncation.truncate(header.getValue(), 200));
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        kept.forEach(
                (k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return Truncation.headers(sb.toString().trim());
    }

    /**
     * Parses {@code Retry-After}, accepting both forms the RFC allows.
     *
     * <p>Negative or nonsensical values are discarded rather than clamped to
     * zero: a receiver sending garbage should fall back to our backoff, not be
     * interpreted as asking for an immediate retry.
     */
    static Integer parseRetryAfter(Header header) {
        if (header == null || header.getValue() == null || header.getValue().isBlank()) {
            return null;
        }
        String value = header.getValue().trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0 || seconds > Integer.MAX_VALUE) {
                return null;
            }
            return (int) seconds;
        } catch (NumberFormatException ignored) {
            // Fall through to the HTTP-date form.
        }
        try {
            Instant target =
                    java.time.ZonedDateTime.parse(
                                    value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
            long seconds = Duration.between(Instant.now(), target).toSeconds();
            return seconds <= 0 ? null : (int) Math.min(seconds, Integer.MAX_VALUE);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private SendResult failure(
            ErrorClass errorClass,
            String message,
            Instant startedAt,
            long startNanos,
            int requestBytes) {
        return new SendResult(
                null,
                null,
                null,
                null,
                errorClass,
                Truncation.errorMessage(message),
                elapsedMillis(startNanos),
                startedAt,
                Instant.now(),
                requestBytes);
    }

    private static int elapsedMillis(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static int length(byte[] body) {
        return body == null ? 0 : body.length;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}

package com.relayforge.retry;

import com.relayforge.delivery.AttemptOutcome;
import com.relayforge.delivery.ErrorClass;
import org.springframework.stereotype.Component;

/**
 * Decides whether a delivery result is worth trying again.
 *
 * <h2>The rule</h2>
 *
 * Retry when the failure plausibly describes a <em>transient</em> condition on
 * the receiver's side, and stop when it describes a <em>permanent</em> one.
 * Retrying a permanent failure is not merely wasteful: it keeps a known-bad
 * delivery out of the dead-letter queue, where a human could actually see and
 * fix it.
 *
 * <table>
 *   <caption>Classification</caption>
 *   <tr><th>Result</th><th>Outcome</th><th>Reasoning</th></tr>
 *   <tr><td>2xx</td><td>SUCCESS</td><td>Delivered.</td></tr>
 *   <tr><td>408 Request Timeout</td><td>RETRY</td><td>The receiver timed itself out.</td></tr>
 *   <tr><td>425 Too Early</td><td>RETRY</td><td>Explicitly asks for a later replay.</td></tr>
 *   <tr><td>429 Too Many Requests</td><td>RETRY</td><td>Capacity, not correctness.</td></tr>
 *   <tr><td>5xx</td><td>RETRY</td><td>Server-side fault, usually transient.</td></tr>
 *   <tr><td>Timeout / connection error</td><td>RETRY</td><td>Never reached the application.</td></tr>
 *   <tr><td>other 4xx</td><td>TERMINAL</td><td>Our request is wrong; repeating it changes nothing.</td></tr>
 *   <tr><td>3xx</td><td>TERMINAL</td><td>Redirects are not followed — see below.</td></tr>
 * </table>
 *
 * <h2>Two cases worth defending</h2>
 *
 * <p><b>401 and 403 are terminal.</b> Tempting to retry — perhaps the receiver's
 * auth service is briefly down — but with a signed webhook these almost always
 * mean the receiver has the wrong secret, which no amount of retrying fixes. Our
 * secret is not going to change mid-schedule. Dead-lettering surfaces it to a
 * human immediately, which is the only thing that resolves it; the delivery is
 * replayable once they have corrected the configuration.
 *
 * <p><b>501 Not Implemented is terminal</b> despite being 5xx. It is a statement
 * about the endpoint's permanent capabilities, not its current health. (502, 503
 * and 504 are retried: those are overload and gateway faults.)
 */
@Component
public class ResponseClassifier {

    /** The classification of one attempt. */
    public record Classification(AttemptOutcome outcome, ErrorClass errorClass, String summary) {

        public boolean retryable() {
            return outcome == AttemptOutcome.RETRYABLE_FAILURE;
        }
    }

    /** Classifies an HTTP response status. */
    public Classification classifyStatus(int status, RetryPolicy policy) {
        if (status >= 200 && status < 300) {
            return new Classification(
                    AttemptOutcome.SUCCESS, ErrorClass.NONE, "HTTP " + status);
        }
        if (status >= 300 && status < 400) {
            return new Classification(
                    AttemptOutcome.TERMINAL_FAILURE,
                    ErrorClass.REDIRECT_NOT_FOLLOWED,
                    "HTTP "
                            + status
                            + ": redirects are not followed, because following one would send a"
                            + " signed payload to a destination that was never validated");
        }
        if (status == 429) {
            return new Classification(
                    AttemptOutcome.RETRYABLE_FAILURE,
                    ErrorClass.RATE_LIMITED,
                    "HTTP 429: the receiver is rate limiting us");
        }
        if (status == 501) {
            return new Classification(
                    AttemptOutcome.TERMINAL_FAILURE,
                    ErrorClass.CLIENT_ERROR,
                    "HTTP 501: the endpoint does not implement this request");
        }
        if (policy.retryableStatusCodes().contains(status)) {
            return new Classification(
                    AttemptOutcome.RETRYABLE_FAILURE,
                    status >= 500 ? ErrorClass.SERVER_ERROR : ErrorClass.CLIENT_ERROR,
                    "HTTP " + status + ": configured as retryable");
        }
        if (status >= 500) {
            return new Classification(
                    AttemptOutcome.RETRYABLE_FAILURE,
                    ErrorClass.SERVER_ERROR,
                    "HTTP " + status + ": server error on the receiving side");
        }
        if (status >= 400) {
            return new Classification(
                    AttemptOutcome.TERMINAL_FAILURE,
                    ErrorClass.CLIENT_ERROR,
                    "HTTP "
                            + status
                            + ": the receiver rejected the request, so repeating it unchanged"
                            + " cannot succeed");
        }
        return new Classification(
                AttemptOutcome.TERMINAL_FAILURE,
                ErrorClass.UNKNOWN,
                "Unexpected HTTP status " + status);
    }

    /** Classifies a transport-level failure, where no HTTP status was received. */
    public Classification classifyTransportError(ErrorClass errorClass, String detail) {
        AttemptOutcome outcome =
                switch (errorClass) {
                    // Nothing reached the application, so the request is still
                    // valid and worth repeating.
                    case CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_REFUSED, DNS_FAILURE ->
                            AttemptOutcome.RETRYABLE_FAILURE;
                    // Retryable, but the request may already have been acted on.
                    // Receivers deduplicate on X-RelayForge-Event-Id; this is the
                    // clearest real example of why that header exists.
                    case RESPONSE_INCOMPLETE -> AttemptOutcome.RETRYABLE_FAILURE;
                    // A destination our own policy forbids, a response too large
                    // to read, or a TLS failure will not resolve by repetition.
                    case BLOCKED_DESTINATION, RESPONSE_TOO_LARGE, TLS_FAILURE ->
                            AttemptOutcome.TERMINAL_FAILURE;
                    default -> AttemptOutcome.RETRYABLE_FAILURE;
                };
        return new Classification(outcome, errorClass, detail);
    }
}

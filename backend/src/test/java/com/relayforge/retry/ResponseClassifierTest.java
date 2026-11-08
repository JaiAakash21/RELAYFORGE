package com.relayforge.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayforge.delivery.AttemptOutcome;
import com.relayforge.delivery.ErrorClass;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests for the retryable/terminal decision. */
class ResponseClassifierTest {

    private final ResponseClassifier classifier = new ResponseClassifier();

    private static final RetryPolicy POLICY =
            new RetryPolicy(
                    6,
                    Duration.ofSeconds(1),
                    Duration.ofMinutes(10),
                    0.2,
                    Duration.ofSeconds(10),
                    Set.of(408, 425, 429),
                    Duration.ofMinutes(15));

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource({
        "200, SUCCESS",
        "201, SUCCESS",
        "202, SUCCESS",
        "204, SUCCESS",
        "299, SUCCESS",
        // Transient conditions on the receiver's side.
        "408, RETRYABLE_FAILURE",
        "425, RETRYABLE_FAILURE",
        "429, RETRYABLE_FAILURE",
        "500, RETRYABLE_FAILURE",
        "502, RETRYABLE_FAILURE",
        "503, RETRYABLE_FAILURE",
        "504, RETRYABLE_FAILURE",
        // Our request is wrong; repeating it unchanged cannot help.
        "400, TERMINAL_FAILURE",
        "401, TERMINAL_FAILURE",
        "403, TERMINAL_FAILURE",
        "404, TERMINAL_FAILURE",
        "410, TERMINAL_FAILURE",
        "422, TERMINAL_FAILURE",
        // Redirects are not followed.
        "301, TERMINAL_FAILURE",
        "302, TERMINAL_FAILURE",
        "307, TERMINAL_FAILURE",
    })
    void classifiesStatus(int status, AttemptOutcome expected) {
        assertThat(classifier.classifyStatus(status, POLICY).outcome()).isEqualTo(expected);
    }

    @Test
    @DisplayName("501 is terminal even though it is a 5xx")
    void notImplementedIsTerminal() {
        // 501 states a permanent capability of the endpoint, not its current
        // health. 502/503/504 are overload and gateway faults and are retried.
        ResponseClassifier.Classification result = classifier.classifyStatus(501, POLICY);
        assertThat(result.outcome()).isEqualTo(AttemptOutcome.TERMINAL_FAILURE);
        assertThat(result.errorClass()).isEqualTo(ErrorClass.CLIENT_ERROR);
    }

    @Test
    @DisplayName("401 and 403 are terminal, so the problem reaches a human")
    void authFailuresAreTerminal() {
        // Tempting to retry, but with a signed webhook these almost always mean
        // the receiver has the wrong secret — which no amount of retrying fixes.
        // Dead-lettering surfaces it immediately, and it stays replayable.
        assertThat(classifier.classifyStatus(401, POLICY).outcome())
                .isEqualTo(AttemptOutcome.TERMINAL_FAILURE);
        assertThat(classifier.classifyStatus(403, POLICY).outcome())
                .isEqualTo(AttemptOutcome.TERMINAL_FAILURE);
    }

    @Test
    @DisplayName("429 is classified as rate limiting, not as a generic client error")
    void rateLimitHasItsOwnErrorClass() {
        ResponseClassifier.Classification result = classifier.classifyStatus(429, POLICY);
        assertThat(result.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorClass()).isEqualTo(ErrorClass.RATE_LIMITED);
    }

    @Test
    @DisplayName("a redirect is reported as not-followed, with the reason")
    void redirectExplainsItself() {
        ResponseClassifier.Classification result = classifier.classifyStatus(302, POLICY);
        assertThat(result.errorClass()).isEqualTo(ErrorClass.REDIRECT_NOT_FOLLOWED);
        assertThat(result.summary()).contains("redirects are not followed");
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorClass.class,
            names = {"CONNECT_TIMEOUT", "READ_TIMEOUT", "CONNECTION_REFUSED", "DNS_FAILURE"})
    @DisplayName("transport failures that never reached the application are retryable")
    void transportFailuresAreRetryable(ErrorClass errorClass) {
        assertThat(classifier.classifyTransportError(errorClass, "x").outcome())
                .isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorClass.class,
            names = {"BLOCKED_DESTINATION", "RESPONSE_TOO_LARGE", "TLS_FAILURE"})
    @DisplayName("failures that repetition cannot fix are terminal")
    void permanentTransportFailuresAreTerminal(ErrorClass errorClass) {
        assertThat(classifier.classifyTransportError(errorClass, "x").outcome())
                .isEqualTo(AttemptOutcome.TERMINAL_FAILURE);
    }

    @Test
    @DisplayName("an incomplete response is retryable but flagged distinctly")
    void incompleteResponseIsRetryable() {
        // The connection was established and the request almost certainly
        // arrived, so a retry may duplicate it on the receiver's side. That is
        // why it is not simply CONNECTION_REFUSED.
        ResponseClassifier.Classification result =
                classifier.classifyTransportError(ErrorClass.RESPONSE_INCOMPLETE, "cut short");
        assertThat(result.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorClass()).isEqualTo(ErrorClass.RESPONSE_INCOMPLETE);
    }

    @Test
    @DisplayName("a configured retryable code wins over the default 4xx rule")
    void configuredCodesAreHonoured() {
        RetryPolicy custom =
                new RetryPolicy(
                        6,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(10),
                        0.2,
                        Duration.ofSeconds(10),
                        Set.of(409),
                        Duration.ofMinutes(15));
        assertThat(classifier.classifyStatus(409, custom).outcome())
                .isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        // Still terminal under the default policy.
        assertThat(classifier.classifyStatus(409, POLICY).outcome())
                .isEqualTo(AttemptOutcome.TERMINAL_FAILURE);
    }
}

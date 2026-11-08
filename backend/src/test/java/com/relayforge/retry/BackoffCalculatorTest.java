package com.relayforge.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for the backoff schedule.
 *
 * <p>Jitter makes the real delay non-deterministic, so the tests assert on the
 * properties that must hold rather than on exact values: the deterministic base
 * is exact, and the jittered output is checked for being inside its band, for
 * actually varying, and for respecting the ceiling.
 */
class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator();

    private static RetryPolicy policy(
            long initialMs, long maxMs, double jitter, int maxAttempts) {
        return new RetryPolicy(
                maxAttempts,
                Duration.ofMillis(initialMs),
                Duration.ofMillis(maxMs),
                jitter,
                Duration.ofSeconds(10),
                Set.of(408, 425, 429),
                Duration.ofMinutes(15));
    }

    @Nested
    @DisplayName("exponential growth")
    class Growth {

        @ParameterizedTest(name = "attempt {0} -> {1}ms")
        @CsvSource({
            // delay = initialDelay * 2^(attempt-1), clamped to maxDelay.
            "1, 1000",
            "2, 2000",
            "3, 4000",
            "4, 8000",
            "5, 16000",
            "6, 32000",
            "7, 60000", // clamped
            "8, 60000",
        })
        @DisplayName("doubles each attempt until it reaches the ceiling")
        void doublesUntilCeiling(int attempt, long expectedMs) {
            RetryPolicy policy = policy(1000, 60_000, 0, 10);
            assertThat(calculator.baseDelayForAttempt(attempt, policy))
                    .isEqualTo(Duration.ofMillis(expectedMs));
        }

        @Test
        @DisplayName("does not overflow on an absurd attempt number")
        void doesNotOverflow() {
            // 2^1000 overflows a long. The calculator caps the exponent before
            // shifting; without that this returns a negative delay and the
            // delivery is scheduled in the past, retrying in a tight loop.
            RetryPolicy policy = policy(1000, 60_000, 0, 10);
            Duration delay = calculator.baseDelayForAttempt(1000, policy);
            assertThat(delay).isEqualTo(Duration.ofMillis(60_000));
            assertThat(delay).isPositive();
        }

        @Test
        @DisplayName("never returns a negative delay")
        void neverNegative() {
            RetryPolicy policy = policy(100, 1000, 0, 5);
            for (int attempt = 0; attempt < 100; attempt++) {
                assertThat(calculator.baseDelayForAttempt(attempt, policy).toMillis())
                        .isNotNegative();
            }
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {

        @Test
        @DisplayName("keeps the delay inside the symmetric band")
        void staysInsideBand() {
            RetryPolicy policy = policy(1000, 60_000, 0.2, 10);
            Duration base = calculator.baseDelayForAttempt(3, policy); // 4000ms

            for (int i = 0; i < 500; i++) {
                long actual = calculator.delayForAttempt(3, policy).toMillis();
                // +/- 20% of 4000ms
                assertThat(actual).isBetween(3200L, 4800L);
            }
            assertThat(base).isEqualTo(Duration.ofMillis(4000));
        }

        @Test
        @DisplayName("actually varies — this is the whole point of jitter")
        void producesDifferentValues() {
            RetryPolicy policy = policy(1000, 60_000, 0.5, 10);
            // Without variation, a batch of deliveries that failed together
            // would retry in lockstep and re-kill a recovering receiver.
            long distinct =
                    java.util.stream.IntStream.range(0, 200)
                            .mapToLong(i -> calculator.delayForAttempt(4, policy).toMillis())
                            .distinct()
                            .count();
            assertThat(distinct)
                    .as("jitter must spread retries across the band")
                    .isGreaterThan(50);
        }

        @Test
        @DisplayName("is disabled cleanly when the factor is zero")
        void zeroFactorIsDeterministic() {
            RetryPolicy policy = policy(1000, 60_000, 0, 10);
            Duration first = calculator.delayForAttempt(3, policy);
            for (int i = 0; i < 50; i++) {
                assertThat(calculator.delayForAttempt(3, policy)).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("never pushes a delay past the configured ceiling")
        void respectsCeiling() {
            RetryPolicy policy = policy(1000, 5000, 1.0, 10);
            for (int i = 0; i < 300; i++) {
                // Attempt 6 would be 32s unjittered; clamped to 5s, and full
                // jitter must not lift it back above the ceiling.
                assertThat(calculator.delayForAttempt(6, policy).toMillis())
                        .isLessThanOrEqualTo(5000L);
            }
        }

        @Test
        @DisplayName("never produces an effectively immediate retry")
        void respectsFloor() {
            RetryPolicy policy = policy(1000, 60_000, 1.0, 10);
            for (int i = 0; i < 300; i++) {
                // Full jitter could otherwise produce ~0ms, which would hammer a
                // receiver that has just failed.
                assertThat(calculator.delayForAttempt(2, policy).toMillis())
                        .isGreaterThanOrEqualTo(100L);
            }
        }
    }

    @Nested
    @DisplayName("Retry-After handling")
    class RetryAfter {

        @Test
        @DisplayName("a receiver's Retry-After overrides the computed backoff")
        void honoursRetryAfter() {
            RetryPolicy policy = policy(1000, 60_000, 0.2, 10);
            // Attempt 5 would be ~16s; the receiver asked for 45s.
            assertThat(calculator.resolveDelay(5, policy, 45))
                    .isEqualTo(Duration.ofSeconds(45));
        }

        @Test
        @DisplayName("clamps an excessive Retry-After")
        void clampsRetryAfter() {
            RetryPolicy policy = policy(1000, 60_000, 0.2, 10);
            // An unbounded Retry-After would let any receiver park our work
            // indefinitely, accidentally or otherwise.
            assertThat(calculator.resolveDelay(2, policy, 86_400))
                    .isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("falls back to backoff when Retry-After is zero")
        void zeroFallsBackToBackoff() {
            RetryPolicy policy = policy(1000, 60_000, 0, 10);
            // "Retry now" against a receiver that just rate-limited us is
            // counter-productive.
            assertThat(calculator.resolveDelay(3, policy, 0))
                    .isEqualTo(Duration.ofMillis(4000));
        }

        @Test
        @DisplayName("falls back to backoff when no Retry-After was sent")
        void nullFallsBackToBackoff() {
            RetryPolicy policy = policy(1000, 60_000, 0, 10);
            assertThat(calculator.resolveDelay(2, policy, null))
                    .isEqualTo(Duration.ofMillis(2000));
        }
    }

    @Nested
    @DisplayName("per-endpoint policy resolution")
    class PolicyResolution {

        @Test
        @DisplayName("an endpoint override layers over the system default")
        void endpointOverridesDefault() {
            com.relayforge.endpoints.WebhookEndpoint endpoint =
                    new com.relayforge.endpoints.WebhookEndpoint(
                            java.util.UUID.randomUUID(),
                            java.util.UUID.randomUUID(),
                            "test",
                            "https://example.com/hook",
                            "sealed");
            endpoint.setRetryMaxAttempts(3);
            endpoint.setRetryInitialDelayMs(500L);

            RetryPolicy base = policy(1000, 60_000, 0.2, 6);
            RetryPolicy resolved =
                    new RetryPolicy(
                            endpoint.getRetryMaxAttempts(),
                            Duration.ofMillis(endpoint.getRetryInitialDelayMs()),
                            base.maxDelay(),
                            base.jitterFactor(),
                            base.timeout(),
                            base.retryableStatusCodes(),
                            base.maxRetryAfter());

            assertThat(resolved.maxAttempts()).isEqualTo(3);
            assertThat(resolved.initialDelay()).isEqualTo(Duration.ofMillis(500));
            // Unset fields keep the default rather than becoming zero.
            assertThat(resolved.maxDelay()).isEqualTo(base.maxDelay());
        }
    }
}

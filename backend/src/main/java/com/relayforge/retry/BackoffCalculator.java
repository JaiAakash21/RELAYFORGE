package com.relayforge.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Computes how long to wait before the next delivery attempt.
 *
 * <h2>Exponential backoff</h2>
 *
 * <pre>{@code  delay = min(initialDelay × 2^(attempt - 1), maxDelay) }</pre>
 *
 * Doubling is the standard response to an overloaded dependency: if a receiver
 * is struggling, retrying at a constant interval adds exactly the load that is
 * causing the failure, and the system never recovers. Doubling backs off faster
 * than the queue can grow. The ceiling exists so a long-dead endpoint does not
 * end up with a retry scheduled days out, which would make the dead-letter queue
 * useless as an operational signal.
 *
 * <h2>Jitter, and why it is not optional</h2>
 *
 * Without randomisation, a receiver that fails a burst of 500 deliveries will be
 * hit by all 500 retries at the same instant, then all 500 again at the next
 * boundary. Pure exponential backoff <em>preserves</em> the synchronisation that
 * caused the outage — a thundering herd that recurs on a doubling schedule.
 * Jitter spreads the herd out, which is the difference between a receiver
 * recovering and a receiver being re-killed by its own backlog.
 *
 * <p>The scheme here is <em>decorrelated</em> jitter: the delay is drawn from
 * {@code [base × (1 − f), base × (1 + f)]} with {@code f} the jitter factor. The
 * two common alternatives were considered:
 *
 * <ul>
 *   <li><b>Full jitter</b> ({@code random(0, base)}) spreads best but can retry
 *       almost immediately after a long wait, which wastes an attempt from a
 *       small budget on an endpoint that is clearly still down.
 *   <li><b>Equal jitter</b> ({@code base/2 + random(0, base/2)}) never exceeds
 *       the base delay, so it cannot spread a herd across the boundary ahead.
 * </ul>
 *
 * Symmetric jitter keeps the expected delay equal to the intended schedule —
 * which matters when the attempt budget is small and each wait has to be
 * meaningful — while still smearing the herd in both directions. The factor is
 * configurable per endpoint, so it is a default rather than a dogma.
 *
 * <h2>Retry-After takes precedence</h2>
 *
 * When a receiver states how long to wait, that is better information than any
 * formula: it comes from the system that knows its own capacity. It is honoured,
 * but clamped to a configured maximum — an unbounded {@code Retry-After} would
 * otherwise let any receiver park our work indefinitely, accidentally or not.
 */
@Component
public class BackoffCalculator {

    /** Delay before the given attempt number, with jitter applied. */
    public Duration delayForAttempt(int nextAttemptNumber, RetryPolicy policy) {
        Duration base = baseDelayForAttempt(nextAttemptNumber, policy);
        return applyJitter(base, policy.jitterFactor(), policy.maxDelay());
    }

    /**
     * The deterministic, un-jittered delay.
     *
     * <p>Exposed separately because it is what the UI displays as the schedule
     * and what tests assert on — the jittered value is intentionally not
     * reproducible.
     */
    public Duration baseDelayForAttempt(int nextAttemptNumber, RetryPolicy policy) {
        int exponent = Math.max(0, nextAttemptNumber - 1);
        // Cap the exponent before shifting: 2^63 overflows, and anything beyond
        // the ceiling is clamped a line later anyway.
        int safeExponent = Math.min(exponent, 40);
        long millis = policy.initialDelay().toMillis();
        long scaled;
        try {
            scaled = Math.multiplyExact(millis, 1L << safeExponent);
        } catch (ArithmeticException overflow) {
            scaled = Long.MAX_VALUE;
        }
        long capped = Math.min(scaled, policy.maxDelay().toMillis());
        return Duration.ofMillis(Math.max(0, capped));
    }

    /**
     * Applies symmetric jitter.
     *
     * <p>Clamped at the bottom to 100 ms so jitter can never produce a retry that
     * is effectively immediate, and at the top to the configured ceiling so
     * jitter cannot push a delay past the maximum the operator chose.
     */
    Duration applyJitter(Duration base, double jitterFactor, Duration maxDelay) {
        if (jitterFactor <= 0) {
            return base;
        }
        double factor = Math.min(1.0, jitterFactor);
        long baseMillis = base.toMillis();
        long spread = (long) (baseMillis * factor);
        if (spread <= 0) {
            return base;
        }
        long offset = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
        long jittered = baseMillis + offset;
        long floor = Math.min(100L, baseMillis);
        return Duration.ofMillis(Math.max(floor, Math.min(jittered, maxDelay.toMillis())));
    }

    /**
     * Resolves the delay for the next attempt, preferring a receiver's own
     * {@code Retry-After} when it supplied one.
     *
     * @param retryAfterSeconds value parsed from the response, or {@code null}
     */
    public Duration resolveDelay(
            int nextAttemptNumber, RetryPolicy policy, Integer retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            Duration requested = Duration.ofSeconds(retryAfterSeconds);
            Duration clamped =
                    requested.compareTo(policy.maxRetryAfter()) > 0
                            ? policy.maxRetryAfter()
                            : requested;
            // A Retry-After of 0 means "retry now", but retrying genuinely
            // immediately against a receiver that just rate-limited us is
            // counter-productive; fall back to the computed backoff.
            if (clamped.isZero()) {
                return delayForAttempt(nextAttemptNumber, policy);
            }
            return clamped;
        }
        return delayForAttempt(nextAttemptNumber, policy);
    }
}

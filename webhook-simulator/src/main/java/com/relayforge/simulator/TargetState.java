package com.relayforge.simulator;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-target state: the configuration plus how many requests it has seen.
 *
 * <p>Held in memory, not persisted. The simulator is a test fixture, and a
 * restart resetting the scenarios is correct behaviour for one — persisting them
 * would mean a restarted demo environment carried stale fault injection into the
 * next run.
 *
 * <p>The attempt counter is an {@link AtomicInteger} because RelayForge may send
 * concurrent requests to the same target, and a scenario that fails "the first
 * two attempts" has to count them correctly under that concurrency or the
 * demonstration is simply wrong.
 */
public class TargetState {

    private final String token;
    private volatile ScenarioConfig config;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicInteger successCount = new AtomicInteger();
    private final Instant createdAt = Instant.now();
    private volatile Instant firstRequestAt;
    private volatile Instant lastRequestAt;

    public TargetState(String token, ScenarioConfig config) {
        this.token = token;
        this.config = config;
    }

    public String token() {
        return token;
    }

    public ScenarioConfig config() {
        return config;
    }

    public void reconfigure(ScenarioConfig config) {
        this.config = config;
        requestCount.set(0);
        failureCount.set(0);
        successCount.set(0);
        this.firstRequestAt = null;
    }

    /** Registers a request and returns its 1-based attempt number. */
    public int registerRequest() {
        Instant now = Instant.now();
        this.lastRequestAt = now;
        int number = requestCount.incrementAndGet();
        if (number == 1) {
            this.firstRequestAt = now;
        }
        return number;
    }

    public void recordFailure() {
        failureCount.incrementAndGet();
    }

    public void recordSuccess() {
        successCount.incrementAndGet();
    }

    public int requestCount() {
        return requestCount.get();
    }

    public int failures() {
        return failureCount.get();
    }

    public int successes() {
        return successCount.get();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant firstRequestAt() {
        return firstRequestAt;
    }

    public Instant lastRequestAt() {
        return lastRequestAt;
    }

    /**
     * For RECOVERY_AFTER_OUTAGE: whether the outage window has elapsed.
     *
     * <p>Measured from the first request rather than from when the target was
     * created, so the clock starts when the demonstration actually begins and not
     * while the user is still reading the scenario description.
     */
    public boolean outageWindowElapsed() {
        Instant start = firstRequestAt;
        if (start == null) {
            return false;
        }
        return Instant.now().isAfter(start.plusSeconds(config.recoverAfterSeconds()));
    }
}

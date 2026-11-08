package com.relayforge.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holds the configured simulator targets.
 *
 * <p>Bounded and self-expiring. A test fixture that grows without limit is a
 * memory leak waiting to happen in whatever environment someone leaves it
 * running in, so there is a hard cap on live targets and idle ones are evicted.
 */
@Component
public class SimulatorRegistry {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRegistry.class);

    /** Maximum concurrently configured targets. */
    private static final int MAX_TARGETS = 500;

    /** Targets untouched for this long are evicted. */
    private static final Duration IDLE_TTL = Duration.ofHours(2);

    private final Map<String, TargetState> targets = new ConcurrentHashMap<>();

    /** Creates or reconfigures a target. */
    public TargetState configure(String token, ScenarioConfig config) {
        if (!targets.containsKey(token) && targets.size() >= MAX_TARGETS) {
            // Make room rather than refusing: the oldest idle target is far more
            // likely to be abandoned than the one being configured right now.
            evictOldest();
        }
        return targets.compute(
                token,
                (key, existing) -> {
                    if (existing == null) {
                        return new TargetState(key, config);
                    }
                    existing.reconfigure(config);
                    return existing;
                });
    }

    public Optional<TargetState> find(String token) {
        return Optional.ofNullable(targets.get(token));
    }

    public Collection<TargetState> all() {
        return targets.values();
    }

    public boolean remove(String token) {
        return targets.remove(token) != null;
    }

    public int size() {
        return targets.size();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    void evictIdleTargets() {
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        int before = targets.size();
        targets
                .entrySet()
                .removeIf(
                        entry -> {
                            TargetState state = entry.getValue();
                            Instant lastActivity =
                                    state.lastRequestAt() != null
                                            ? state.lastRequestAt()
                                            : state.createdAt();
                            return lastActivity.isBefore(cutoff);
                        });
        int evicted = before - targets.size();
        if (evicted > 0) {
            log.info("Evicted {} idle simulator targets", evicted);
        }
    }

    private void evictOldest() {
        targets.values().stream()
                .min(java.util.Comparator.comparing(TargetState::createdAt))
                .ifPresent(oldest -> targets.remove(oldest.token()));
    }
}

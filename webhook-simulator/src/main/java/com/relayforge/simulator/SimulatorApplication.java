package com.relayforge.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The RelayForge webhook simulator.
 *
 * <p>A deliberately misbehaving webhook receiver. It exists so the Reliability
 * Playground can demonstrate real retry, backoff and dead-letter behaviour
 * against failures that are reproducible on demand — rather than waiting for a
 * third-party endpoint to break on its own.
 *
 * <h2>Containment</h2>
 *
 * This service makes no outbound requests of any kind. It only answers. It is
 * reachable from RelayForge solely because its Docker service name appears on the
 * development egress allowlist, and it listens on an internal port that is not
 * published to the host by default. The SSRF policy is not relaxed to accommodate
 * it — a single named host is exempted from the private-address rule, and nothing
 * else changes.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}

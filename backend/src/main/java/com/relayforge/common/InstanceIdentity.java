package com.relayforge.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A stable identifier for this process.
 *
 * <p>Used as the lease owner on claimed deliveries and claimed outbox rows, and
 * recorded on every delivery attempt. During an incident the first useful
 * question is "which worker was holding this?", and that is unanswerable without
 * an identity that appears in both the database and the logs.
 *
 * <p>Combines the hostname (in a container, the container id — which is what
 * Docker and Kubernetes show) with a short random suffix. The suffix matters
 * because two processes can share a hostname, and two lease owners that collide
 * would let one process release a lease it does not hold.
 */
@Component
public class InstanceIdentity {

    private final String id;

    public InstanceIdentity() {
        this.id = buildId();
    }

    private static String buildId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        if (host.length() > 48) {
            host = host.substring(0, 48);
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String id() {
        return id;
    }
}

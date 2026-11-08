package com.relayforge.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hc.client5.http.DnsResolver;

/**
 * A {@link DnsResolver} that returns only addresses {@link DestinationPolicy}
 * has already approved.
 *
 * <h2>Why this exists</h2>
 *
 * Validating a URL and then handing the hostname to an HTTP client means the
 * hostname is resolved twice: once by the check, once by the client when it
 * opens the socket. Between the two, the DNS answer can change. A record with a
 * one-second TTL can return a public address to the validator and
 * {@code 127.0.0.1} to the connection — DNS rebinding, and it defeats hostname
 * validation completely.
 *
 * <p>Apache HttpClient lets the DNS resolver be replaced, which removes the
 * second lookup altogether. The policy resolves the hostname once, decides, and
 * registers the approved addresses here; the client then connects to exactly
 * those addresses. There is no second resolution to poison, so the window is
 * closed rather than narrowed.
 *
 * <h2>Scope and lifetime</h2>
 *
 * A pin is registered immediately before a request and removed immediately
 * after, in a {@code finally} block, so the map holds only in-flight requests
 * and never becomes a stale address cache. The pin is per hostname; concurrent
 * attempts to the same host share it, which is correct because the validated
 * address set for a host is the same regardless of which delivery needs it.
 *
 * <p>A hostname that was never pinned is refused rather than resolved. Failing
 * closed means a future code path that forgets to validate cannot quietly fall
 * back to unrestricted DNS — it breaks loudly in tests instead.
 */
public class PinnedDnsResolver implements DnsResolver {

    private final Map<String, List<InetAddress>> pins = new ConcurrentHashMap<>();

    /** Registers the approved addresses for a hostname. */
    public void pin(String host, List<InetAddress> addresses) {
        pins.put(normalise(host), List.copyOf(addresses));
    }

    /** Removes a pin. Always called from a {@code finally} block. */
    public void unpin(String host) {
        pins.remove(normalise(host));
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        List<InetAddress> pinned = pins.get(normalise(host));
        if (pinned == null || pinned.isEmpty()) {
            // Fail closed. See the class comment.
            throw new UnknownHostException(
                    "No validated address is pinned for '"
                            + host
                            + "'. Outbound requests must go through DestinationPolicy.validate()"
                            + " before being issued.");
        }
        return pinned.toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // Deliberately does not perform a reverse lookup: the canonical name is
        // not needed for delivery, and resolving it would reintroduce an
        // unvalidated DNS query on the outbound path.
        return host;
    }

    int pinnedHostCount() {
        return pins.size();
    }

    private static String normalise(String host) {
        if (host == null) {
            return "";
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        while (lower.endsWith(".") && lower.length() > 1) {
            lower = lower.substring(0, lower.length() - 1);
        }
        return lower;
    }
}

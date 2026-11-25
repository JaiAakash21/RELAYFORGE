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
 * A pin is registered immediately before a request and released in a
 * {@code finally} block. Concurrent requests to one host retain the pin until
 * the last request finishes.
 *
 * <p>A hostname that was never pinned is refused rather than resolved. Failing
 * closed means a future code path that forgets to validate cannot quietly fall
 * back to unrestricted DNS — it breaks loudly in tests instead.
 */
public class PinnedDnsResolver implements DnsResolver {

    private final Map<String, Pin> pins = new ConcurrentHashMap<>();

    private record Pin(List<InetAddress> addresses, int users) {}

    /** Registers the approved addresses for a hostname. */
    public void pin(String host, List<InetAddress> addresses) {
        pins.compute(
                normalise(host),
                (key, current) ->
                        current == null
                                ? new Pin(List.copyOf(addresses), 1)
                                : new Pin(current.addresses(), current.users() + 1));
    }

    /** Removes a pin. Always called from a {@code finally} block. */
    public void unpin(String host) {
        pins.computeIfPresent(
                normalise(host),
                (key, current) ->
                        current.users() == 1
                                ? null
                                : new Pin(current.addresses(), current.users() - 1));
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Pin pin = pins.get(normalise(host));
        if (pin == null || pin.addresses().isEmpty()) {
            // Fail closed. See the class comment.
            throw new UnknownHostException(
                    "No validated address is pinned for '"
                            + host
                            + "'. Outbound requests must go through DestinationPolicy.validate()"
                            + " before being issued.");
        }
        return pin.addresses().toArray(InetAddress[]::new);
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

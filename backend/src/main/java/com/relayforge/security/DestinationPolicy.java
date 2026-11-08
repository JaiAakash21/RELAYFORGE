package com.relayforge.security;

import com.relayforge.config.RelayForgeProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether RelayForge is willing to send an HTTP request to a
 * user-supplied URL.
 *
 * <h2>The problem</h2>
 *
 * A webhook platform's entire purpose is to make outbound HTTP requests to
 * addresses its users choose. That is textbook Server-Side Request Forgery: a
 * tenant who registers {@code http://169.254.169.254/latest/meta-data/} is
 * asking our infrastructure to fetch cloud credentials on their behalf and hand
 * back the response body — which the delivery-attempt viewer would faithfully
 * display. A tenant who registers {@code http://postgres:5432} is probing our
 * internal network from inside it.
 *
 * <p>Unlike most SSRF surfaces this one cannot be removed by refusing
 * user-supplied URLs, so it has to be contained instead.
 *
 * <h2>What is enforced</h2>
 *
 * <ol>
 *   <li><b>Scheme.</b> {@code https} only in the {@code prod} profile. Plain
 *       {@code http} would put the signing secret's protection entirely on the
 *       HMAC and leak every payload in transit.
 *   <li><b>Port.</b> Restricted to an explicit set. Blocks the "use us as a port
 *       scanner" class of abuse against internal services.
 *   <li><b>Hostname shape.</b> No userinfo (the {@code @} trick that makes a URL
 *       look like it points somewhere else), no trailing-dot bypasses.
 *   <li><b>Resolved addresses.</b> The hostname is resolved and <em>every</em>
 *       returned address is checked. Loopback, private, link-local (which is
 *       where every cloud metadata service lives), carrier-grade NAT, multicast,
 *       unspecified and reserved ranges are rejected, as are IPv6 unique-local
 *       addresses and IPv4-mapped IPv6 forms of all of the above.
 *   <li><b>Resolution timeout.</b> DNS is performed on a bounded executor, so a
 *       deliberately slow authoritative server cannot pin a worker thread.
 * </ol>
 *
 * <h2>The IPv4-mapped IPv6 subtlety</h2>
 *
 * {@code ::ffff:127.0.0.1} is loopback written as an IPv6 address.
 * {@link InetAddress#isLoopbackAddress()} does handle that form, but
 * {@code isSiteLocalAddress()} does not consistently cover mapped private
 * ranges, so mapped addresses are explicitly unwrapped to their IPv4 form and
 * re-checked. Skipping this is a common, quiet way for an allowlist to be
 * bypassed.
 *
 * <h2>What this cannot do, stated honestly</h2>
 *
 * This class validates the addresses a hostname resolves to <em>now</em>. The
 * HTTP client then resolves the hostname again when it opens the connection. A
 * DNS entry with a very short TTL can return a public address to this check and
 * a private one to the connection — classic DNS rebinding.
 *
 * <p>RelayForge closes that window in the layer below, not here:
 * {@link PinnedDnsResolver} hands the HTTP client the exact addresses this class
 * approved, so the connection cannot be made to an address that was never
 * validated. {@link #validate} is the policy; the resolver is the enforcement.
 *
 * <p>Neither can defend against a destination that is public at connect time and
 * proxies internally, nor against a route that becomes internal below the IP
 * layer. In production the correct final control is network-level egress
 * filtering — a NAT gateway or egress proxy that cannot reach RFC1918 space at
 * all — which is recommended in docs/SECURITY.md. Application-level validation
 * is defence in depth, not a substitute for it.
 */
@Component
public class DestinationPolicy {

    private static final Logger log = LoggerFactory.getLogger(DestinationPolicy.class);

    /** Hostnames that are never acceptable, regardless of what they resolve to. */
    private static final Set<String> ALWAYS_BLOCKED_HOSTS =
            Set.of(
                    "metadata.google.internal",
                    "metadata.goog",
                    "instance-data",
                    "instance-data.ec2.internal");

    /**
     * Cloud instance-metadata addresses, blocked by address as well as by name.
     *
     * <p>Listed explicitly even though they fall inside link-local, which is
     * already rejected: these are the highest-value SSRF targets in existence,
     * and a named check produces a clear log line and a clear error class when
     * someone tries.
     */
    private static final Set<String> METADATA_ADDRESSES =
            Set.of(
                    "169.254.169.254", // AWS / Azure / GCP / DigitalOcean / Oracle
                    "169.254.170.2", // AWS ECS task metadata
                    "100.100.100.200", // Alibaba Cloud
                    "fd00:ec2::254" // AWS IPv6 IMDS
                    );

    private final RelayForgeProperties.Egress config;
    private final ExecutorService dnsExecutor;

    public DestinationPolicy(RelayForgeProperties properties) {
        this.config = properties.egress();
        // A small dedicated pool, so a hostile DNS server delays only resolution
        // and never the delivery worker threads themselves.
        this.dnsExecutor =
                Executors.newCachedThreadPool(
                        Thread.ofPlatform().name("relayforge-dns-", 0).daemon(true).factory());
    }

    /** Why a destination was rejected. */
    public record Rejection(String reason, String detail) {}

    /** An approved destination together with the exact addresses that were approved. */
    public record Approved(URI uri, String host, int port, List<InetAddress> addresses) {}

    /** Thrown when a destination is not permitted. */
    public static class BlockedDestinationException extends RuntimeException {
        private final String reasonCode;

        public BlockedDestinationException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    /**
     * Validates a webhook URL without resolving DNS.
     *
     * <p>Used at endpoint-creation time to give immediate, useful feedback on the
     * obviously wrong cases (bad scheme, literal private IP, blocked port) —
     * registering an endpoint should not depend on the destination being
     * resolvable at that moment.
     */
    public void validateSyntax(String rawUrl) {
        URI uri = parse(rawUrl);
        checkScheme(uri);
        checkUserInfo(uri);
        String host = normaliseHost(uri);
        checkPort(uri);
        checkBlockedHostname(host);

        // If the host is already a literal IP there is nothing to resolve, so the
        // address policy can and should be applied right now.
        if (isIpLiteral(host)) {
            InetAddress literal;
            try {
                literal = InetAddress.getByName(stripBrackets(host));
            } catch (UnknownHostException e) {
                throw new BlockedDestinationException(
                        "invalid_host", "Destination host is not a valid address");
            }
            assertAddressAllowed(literal, host);
        }
    }

    /**
     * Fully validates a destination, resolving DNS and checking every address.
     *
     * @return the approved destination, carrying the vetted addresses for the
     *     HTTP client to connect to
     * @throws BlockedDestinationException if the destination is not permitted
     */
    public Approved validate(String rawUrl) {
        URI uri = parse(rawUrl);
        checkScheme(uri);
        checkUserInfo(uri);
        String host = normaliseHost(uri);
        int port = effectivePort(uri);
        checkPort(uri);
        checkBlockedHostname(host);

        List<InetAddress> resolved = resolve(host);
        if (resolved.isEmpty()) {
            throw new BlockedDestinationException(
                    "dns_empty", "Destination hostname did not resolve to any address");
        }

        boolean allowlisted = isDevAllowlisted(host);
        List<InetAddress> approved = new ArrayList<>();
        for (InetAddress address : resolved) {
            // The metadata check is absolute: an allowlist entry never unlocks it.
            assertNotMetadata(address);
            if (allowlisted) {
                approved.add(address);
                continue;
            }
            assertAddressAllowed(address, host);
            approved.add(address);
        }

        if (allowlisted) {
            log.debug(
                    "Destination host {} permitted via the development allowlist; this exemption"
                            + " must never be enabled in the prod egress profile",
                    host);
        }
        return new Approved(uri, host, port, List.copyOf(approved));
    }

    /** True when the host is exempt from the private-address block in the dev profile. */
    public boolean isDevAllowlisted(String host) {
        if (config.profile() != RelayForgeProperties.Egress.Profile.DEV) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        return config.devAllowlist().stream()
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .anyMatch(candidate::equals);
    }

    // ----------------------------------------------------------------- checks

    private URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BlockedDestinationException("invalid_url", "Destination URL is required");
        }
        if (rawUrl.length() > 2048) {
            throw new BlockedDestinationException(
                    "invalid_url", "Destination URL exceeds 2048 characters");
        }
        // A URL containing whitespace or control characters is either a mistake
        // or an attempt at request smuggling via header injection.
        for (char c : rawUrl.toCharArray()) {
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7f) {
                throw new BlockedDestinationException(
                        "invalid_url", "Destination URL contains whitespace or control characters");
            }
        }
        try {
            URI uri = new URI(rawUrl);
            if (uri.getHost() == null) {
                throw new BlockedDestinationException(
                        "invalid_url", "Destination URL has no host component");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new BlockedDestinationException("invalid_url", "Destination URL is malformed");
        }
    }

    private void checkScheme(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean httpsOnly = config.profile() == RelayForgeProperties.Egress.Profile.PROD;
        if (httpsOnly) {
            if (!"https".equals(scheme)) {
                throw new BlockedDestinationException(
                        "scheme_not_allowed",
                        "Only https destinations are accepted (the prod egress profile is active)");
            }
        } else if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new BlockedDestinationException(
                    "scheme_not_allowed", "Only http and https destinations are accepted");
        }
    }

    /**
     * Rejects {@code https://trusted.example.com@attacker.test/}.
     *
     * <p>Userinfo makes a URL read as though it points at one host while actually
     * pointing at another. It has no legitimate use for a webhook destination.
     */
    private void checkUserInfo(URI uri) {
        if (uri.getUserInfo() != null || uri.getAuthority() == null
                || uri.getAuthority().contains("@")) {
            if (uri.getUserInfo() != null
                    || (uri.getAuthority() != null && uri.getAuthority().contains("@"))) {
                throw new BlockedDestinationException(
                        "userinfo_not_allowed",
                        "Destination URL must not contain embedded credentials");
            }
        }
    }

    private void checkPort(URI uri) {
        int port = effectivePort(uri);
        if (!config.allowedPorts().contains(port)) {
            throw new BlockedDestinationException(
                    "port_not_allowed", "Destination port " + port + " is not permitted");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Normalises the hostname.
     *
     * <p>A trailing dot ({@code example.com.}) is a valid FQDN that string
     * comparisons treat as a different host from {@code example.com} — exactly
     * the kind of mismatch that turns a blocklist into a suggestion.
     */
    private static String normaliseHost(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        while (host.endsWith(".") && host.length() > 1) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private void checkBlockedHostname(String host) {
        if (ALWAYS_BLOCKED_HOSTS.contains(host)) {
            throw new BlockedDestinationException(
                    "metadata_host_blocked",
                    "Destination resolves to a cloud instance metadata service");
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[")) {
            return true;
        }
        // An all-digits-and-dots host can only be an IPv4 literal.
        return host.matches("^[0-9.]+$") || host.contains(":");
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private List<InetAddress> resolve(String host) {
        Callable<List<InetAddress>> task =
                () -> List.of(InetAddress.getAllByName(stripBrackets(host)));
        Future<List<InetAddress>> future = dnsExecutor.submit(task);
        try {
            return future.get(config.dnsTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BlockedDestinationException(
                    "dns_timeout",
                    "DNS resolution for the destination did not complete within "
                            + config.dnsTimeout().toMillis()
                            + " ms");
        } catch (ExecutionException e) {
            throw new BlockedDestinationException(
                    "dns_failure", "Destination hostname could not be resolved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlockedDestinationException(
                    "dns_failure", "DNS resolution was interrupted");
        }
    }

    private void assertNotMetadata(InetAddress address) {
        InetAddress effective = unwrapMapped(address);
        if (METADATA_ADDRESSES.contains(effective.getHostAddress())
                || METADATA_ADDRESSES.contains(address.getHostAddress())) {
            throw new BlockedDestinationException(
                    "metadata_address_blocked",
                    "Destination resolves to a cloud instance metadata address");
        }
    }

    /**
     * Rejects any address that is not safely on the public internet.
     *
     * <p>Written as an explicit deny-list of address categories rather than
     * relying on a single library predicate, because no single JDK predicate
     * covers all of them and the gaps are precisely where bypasses live.
     */
    private void assertAddressAllowed(InetAddress original, String host) {
        InetAddress address = unwrapMapped(original);
        String shown = address.getHostAddress();

        if (address.isLoopbackAddress()) {
            throw blocked("loopback_blocked", host, shown, "a loopback address");
        }
        if (address.isAnyLocalAddress()) {
            throw blocked("unspecified_blocked", host, shown, "the unspecified address");
        }
        if (address.isLinkLocalAddress()) {
            throw blocked(
                    "link_local_blocked",
                    host,
                    shown,
                    "a link-local address (this range contains cloud metadata services)");
        }
        if (address.isSiteLocalAddress()) {
            throw blocked("private_blocked", host, shown, "a private (RFC1918) address");
        }
        if (address.isMulticastAddress()) {
            throw blocked("multicast_blocked", host, shown, "a multicast address");
        }

        if (address instanceof Inet4Address ipv4) {
            byte[] octets = ipv4.getAddress();
            int first = octets[0] & 0xff;
            int second = octets[1] & 0xff;

            // 100.64.0.0/10 — carrier-grade NAT. Not covered by isSiteLocalAddress.
            if (first == 100 && second >= 64 && second <= 127) {
                throw blocked(
                        "cgnat_blocked", host, shown, "a carrier-grade NAT address (100.64.0.0/10)");
            }
            // 0.0.0.0/8 — "this network".
            if (first == 0) {
                throw blocked("reserved_blocked", host, shown, "in the reserved 0.0.0.0/8 range");
            }
            // 192.0.0.0/24, 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 — special-use.
            if (first == 192 && second == 0) {
                throw blocked("reserved_blocked", host, shown, "in a reserved 192.0.0.0/24 range");
            }
            if (first == 198 && (second == 18 || second == 19)) {
                throw blocked(
                        "reserved_blocked", host, shown, "in the benchmarking range 198.18.0.0/15");
            }
            // 240.0.0.0/4 and above — reserved / broadcast.
            if (first >= 240) {
                throw blocked("reserved_blocked", host, shown, "in the reserved 240.0.0.0/4 range");
            }
        }

        if (address instanceof Inet6Address ipv6) {
            byte[] bytes = ipv6.getAddress();
            // fc00::/7 — IPv6 unique local addresses, the IPv6 analogue of RFC1918.
            // isSiteLocalAddress() only matches the deprecated fec0::/10.
            if ((bytes[0] & 0xfe) == 0xfc) {
                throw blocked(
                        "ipv6_unique_local_blocked",
                        host,
                        shown,
                        "an IPv6 unique-local address (fc00::/7)");
            }
            // 2001:db8::/32 — documentation range.
            if ((bytes[0] & 0xff) == 0x20
                    && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8) {
                throw blocked(
                        "reserved_blocked", host, shown, "in the IPv6 documentation range");
            }
        }
    }

    /**
     * Converts an IPv4-mapped IPv6 address to its IPv4 form.
     *
     * <p>Without this, {@code ::ffff:10.0.0.1} would be evaluated only by the
     * IPv6 rules and would slip past the RFC1918 check.
     */
    private static InetAddress unwrapMapped(InetAddress address) {
        if (!(address instanceof Inet6Address ipv6)) {
            return address;
        }
        byte[] bytes = ipv6.getAddress();
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            try {
                return InetAddress.getByAddress(
                        new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            } catch (UnknownHostException e) {
                return address;
            }
        }
        return address;
    }

    private BlockedDestinationException blocked(
            String code, String host, String address, String description) {
        // The message names the category but not an internal topology detail the
        // caller did not already supply.
        return new BlockedDestinationException(
                code,
                "Destination "
                        + host
                        + " resolves to "
                        + address
                        + ", which is "
                        + description
                        + " and is not a permitted webhook destination");
    }
}

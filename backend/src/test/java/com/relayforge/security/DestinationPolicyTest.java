package com.relayforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relayforge.config.RelayForgeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF policy tests.
 *
 * <p>These are the most security-critical tests in the project. A webhook
 * platform exists to make outbound requests to addresses its users choose, so
 * the only thing standing between a tenant and our internal network is this
 * class.
 *
 * <p>The cases use IP literals wherever possible so they assert on the policy
 * itself rather than on whatever DNS happens to return in the test environment.
 */
class DestinationPolicyTest {

    private static DestinationPolicy policy(
            RelayForgeProperties.Egress.Profile profile, Set<String> allowlist) {
        RelayForgeProperties properties =
                new RelayForgeProperties(
                        RelayForgeProperties.Role.ALL,
                        new RelayForgeProperties.Jwt(
                                "x".repeat(80), Duration.ofMinutes(15), Duration.ofDays(7), "rf"),
                        new RelayForgeProperties.Crypto("a".repeat(44), Duration.ofHours(24)),
                        new RelayForgeProperties.Delivery(
                                4,
                                Duration.ofSeconds(60),
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(10),
                                65536,
                                1048576,
                                200,
                                20,
                                "RelayForge/test"),
                        new RelayForgeProperties.Retry(
                                6,
                                Duration.ofSeconds(5),
                                Duration.ofHours(1),
                                0.2,
                                Set.of(408, 425, 429),
                                Duration.ofMinutes(15),
                                3),
                        new RelayForgeProperties.Outbox(
                                100,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(30),
                                10,
                                200,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(90),
                                Duration.ofSeconds(15),
                                Duration.ofHours(24)),
                        new RelayForgeProperties.Egress(
                                profile,
                                allowlist,
                                Duration.ofSeconds(2),
                                Set.of(80, 443, 8080, 8081, 8443, 3000)),
                        new RelayForgeProperties.RateLimits(
                                100, 200, 50, 100, 20, 40, 20, true),
                        new RelayForgeProperties.Ingestion(262144, 100, Set.of("relayforge.")),
                        new RelayForgeProperties.Playground(
                                "http://localhost:8081",
                                6,
                                Duration.ofSeconds(3),
                                Duration.ofSeconds(30)),
                        new RelayForgeProperties.Cors(List.of("http://localhost:3000")),
                        new RelayForgeProperties.Demo(false, null, null, null));
        return new DestinationPolicy(properties);
    }

    private final DestinationPolicy dev =
            policy(RelayForgeProperties.Egress.Profile.DEV, Set.of("localhost"));
    private final DestinationPolicy prod =
            policy(RelayForgeProperties.Egress.Profile.PROD, Set.of());

    @Nested
    @DisplayName("blocked address ranges")
    class BlockedRanges {

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    // Cloud instance metadata — the highest-value SSRF target
                    // in existence. 169.254.169.254 serves credentials on AWS,
                    // Azure, GCP, DigitalOcean and Oracle.
                    "http://169.254.169.254/latest/meta-data/",
                    "http://169.254.170.2/v2/credentials",
                    "http://100.100.100.200/latest/meta-data/",
                    // Loopback: reaches anything bound on the worker itself.
                    "http://127.0.0.1/admin",
                    "http://127.0.0.2/admin",
                    "http://[::1]/admin",
                    // RFC1918 private space: the internal network.
                    "http://10.0.0.1/",
                    "http://172.16.5.4/",
                    "http://192.168.1.1/",
                    // Link-local, which contains the metadata ranges above.
                    "http://169.254.1.1/",
                    // Carrier-grade NAT — not covered by isSiteLocalAddress().
                    "http://100.64.0.1/",
                    "http://100.127.255.254/",
                    // Reserved and special-use.
                    "http://0.0.0.0/",
                    "http://0.1.2.3/",
                    "http://192.0.0.1/",
                    "http://198.18.0.1/",
                    "http://240.0.0.1/",
                    "http://255.255.255.255/",
                    // IPv6 unique-local: isSiteLocalAddress() only matches the
                    // deprecated fec0::/10, so fc00::/7 needs its own check.
                    "http://[fc00::1]/",
                    "http://[fd12:3456::1]/",
                    // Multicast.
                    "http://224.0.0.1/",
                })
        @DisplayName("are refused")
        void refusesBlockedAddress(String url) {
            assertThatThrownBy(() -> dev.validateSyntax(url))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 loopback is refused")
        void refusesMappedLoopback() {
            // ::ffff:127.0.0.1 is loopback written as IPv6. Unwrapping mapped
            // addresses before checking is a step that is easy to skip, and
            // skipping it is a quiet bypass of the entire IPv4 ruleset.
            assertThatThrownBy(() -> dev.validateSyntax("http://[::ffff:127.0.0.1]/"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class)
                    .hasMessageContaining("loopback");
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 private address is refused")
        void refusesMappedPrivate() {
            assertThatThrownBy(() -> dev.validateSyntax("http://[::ffff:10.0.0.1]/"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class)
                    .hasMessageContaining("private");
        }

        @Test
        @DisplayName("the metadata address is refused even for an allowlisted host")
        void allowlistNeverUnlocksMetadata() {
            // The allowlist relaxes the private-address rule for a named dev
            // service. It must never unlock the metadata service.
            DestinationPolicy permissive =
                    policy(
                            RelayForgeProperties.Egress.Profile.DEV,
                            Set.of("169.254.169.254", "metadata.google.internal"));
            assertThatThrownBy(() -> permissive.validate("http://metadata.google.internal/"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class)
                    .hasMessageContaining("metadata");
        }
    }

    @Nested
    @DisplayName("URL shape")
    class UrlShape {

        @Test
        @DisplayName("embedded credentials are refused")
        void refusesUserInfo() {
            // https://trusted.example.com@attacker.test/ reads as though it
            // points at the first host and actually points at the second.
            assertThatThrownBy(() -> dev.validateSyntax("https://trusted.test@attacker.test/"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class)
                    .hasMessageContaining("credentials");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "gopher://example.com/"})
        @DisplayName("non-HTTP schemes are refused")
        void refusesOtherSchemes(String url) {
            assertThatThrownBy(() -> dev.validateSyntax(url))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {22, 25, 3306, 5432, 6379, 9092, 11211, 27017})
        @DisplayName("ports belonging to internal services are refused")
        void refusesDisallowedPorts(int port) {
            // Blocks the "use the webhook sender as a port scanner" class of
            // abuse against internal infrastructure.
            assertThatThrownBy(() -> dev.validateSyntax("https://example.com:" + port + "/"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("whitespace and control characters are refused")
        void refusesControlCharacters() {
            assertThatThrownBy(() -> dev.validateSyntax("https://example.com/a b"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
            assertThatThrownBy(() -> dev.validateSyntax("https://example.com/a\nb"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }

        @Test
        @DisplayName("an over-long URL is refused")
        void refusesOverlongUrl() {
            assertThatThrownBy(
                            () -> dev.validateSyntax("https://example.com/" + "a".repeat(3000)))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }

        @Test
        @DisplayName("a blank or malformed URL is refused")
        void refusesMalformed() {
            assertThatThrownBy(() -> dev.validateSyntax(""))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
            assertThatThrownBy(() -> dev.validateSyntax(null))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
            assertThatThrownBy(() -> dev.validateSyntax("not-a-url"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
            assertThatThrownBy(() -> dev.validateSyntax("https:///nohost"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }
    }

    @Nested
    @DisplayName("egress profiles")
    class Profiles {

        @Test
        @DisplayName("prod refuses plain http")
        void prodRequiresHttps() {
            assertThatThrownBy(() -> prod.validateSyntax("http://example.com/hook"))
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class)
                    .hasMessageContaining("https");
            assertThatCode(() -> prod.validateSyntax("https://example.com/hook"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("dev permits plain http for ordinary public hosts")
        void devAllowsHttp() {
            assertThatCode(() -> dev.validateSyntax("http://example.com/hook"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the allowlist applies only in the dev profile")
        void allowlistIsDevOnly() {
            DestinationPolicy prodWithAllowlist =
                    policy(RelayForgeProperties.Egress.Profile.PROD, Set.of("localhost"));
            assertThat(prodWithAllowlist.isDevAllowlisted("localhost")).isFalse();
            assertThat(dev.isDevAllowlisted("localhost")).isTrue();
        }

        @Test
        @DisplayName("allowlist matching is case-insensitive but exact")
        void allowlistIsExact() {
            assertThat(dev.isDevAllowlisted("LOCALHOST")).isTrue();
            // Not a prefix or suffix match: evil-localhost must not slip through.
            assertThat(dev.isDevAllowlisted("evil-localhost")).isFalse();
            assertThat(dev.isDevAllowlisted("localhost.attacker.test")).isFalse();
        }
    }

    @Nested
    @DisplayName("accepted destinations")
    class Accepted {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://example.com/webhooks",
                    "https://api.example.com:443/hooks/relayforge",
                    "https://example.com:8443/hook",
                    "https://8.8.8.8/hook",
                })
        @DisplayName("ordinary public destinations pass")
        void acceptsPublicDestinations(String url) {
            assertThatCode(() -> dev.validateSyntax(url)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a trailing dot does not create a bypass")
        void normalisesTrailingDot() {
            // example.com. is a valid FQDN that string comparison treats as a
            // different host from example.com — exactly the kind of mismatch
            // that turns a blocklist into a suggestion.
            assertThat(dev.isDevAllowlisted("localhost.")).isTrue();
        }
    }
}

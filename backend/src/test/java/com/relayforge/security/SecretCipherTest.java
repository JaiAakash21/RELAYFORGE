package com.relayforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relayforge.config.RelayForgeProperties;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for AES-256-GCM sealing of webhook secrets. */
class SecretCipherTest {

    private static SecretCipher cipher(String key) {
        RelayForgeProperties properties =
                new RelayForgeProperties(
                        RelayForgeProperties.Role.ALL,
                        new RelayForgeProperties.Jwt(
                                "x".repeat(80), Duration.ofMinutes(15), Duration.ofDays(7), "rf"),
                        new RelayForgeProperties.Crypto(key, Duration.ofHours(24)),
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
                                Set.of(429),
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
                                RelayForgeProperties.Egress.Profile.DEV,
                                Set.of(),
                                Duration.ofSeconds(2),
                                Set.of(443)),
                        new RelayForgeProperties.RateLimits(100, 200, 50, 100, 20, 40, 20, true),
                        new RelayForgeProperties.Ingestion(262144, 100, Set.of()),
                        new RelayForgeProperties.Playground(
                                "http://localhost:8081",
                                6,
                                Duration.ofSeconds(3),
                                Duration.ofSeconds(30)),
                        new RelayForgeProperties.Cors(List.of("http://localhost:3000")),
                        new RelayForgeProperties.Demo(false, null, null, null));
        return new SecretCipher(properties);
    }

    /** A valid base64-encoded 32-byte key. */
    private static String validKey() {
        byte[] material = new byte[32];
        new java.security.SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    @Test
    @DisplayName("a sealed secret round-trips")
    void roundTrips() {
        SecretCipher cipher = cipher(validKey());
        String plaintext = "whsec_super_secret_value_12345";
        String sealed = cipher.encrypt(plaintext);

        assertThat(sealed).isNotEqualTo(plaintext);
        assertThat(sealed).startsWith("v1.");
        assertThat(cipher.decrypt(sealed)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("the same plaintext seals differently each time")
    void usesFreshIvEachTime() {
        // A fresh random IV per encryption. Reusing an IV under GCM is
        // catastrophic rather than merely weak — it leaks the XOR of the
        // plaintexts and can forge the authentication tag.
        SecretCipher cipher = cipher(validKey());
        String plaintext = "whsec_same_value";
        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
        assertThat(cipher.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("tampering with the ciphertext fails decryption")
    void detectsTampering() {
        // This is why GCM rather than CBC: an attacker with write access to the
        // database must not be able to flip bits and have us sign with a key
        // they influenced. Authenticated encryption turns that into a failure.
        SecretCipher cipher = cipher(validKey());
        String sealed = cipher.encrypt("whsec_original");

        String[] parts = sealed.split("\\.", 3);
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
        ciphertext[0] ^= 0x01;
        String tampered =
                parts[0]
                        + "."
                        + parts[1]
                        + "."
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tampered");
    }

    @Test
    @DisplayName("a value sealed under a different key cannot be opened")
    void rejectsForeignKey() {
        SecretCipher first = cipher(validKey());
        SecretCipher second = cipher(validKey());
        String sealed = first.encrypt("whsec_value");

        assertThatThrownBy(() -> second.decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a malformed envelope is rejected")
    void rejectsMalformedEnvelope() {
        SecretCipher cipher = cipher(validKey());
        for (String bad : new String[] {"not-sealed", "v1.only-two", "v2.aaa.bbb", ""}) {
            assertThatThrownBy(() -> cipher.decrypt(bad))
                    .as("value: %s", bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("startup fails loudly on a placeholder key")
    void refusesPlaceholderKey() {
        // A system that appears to work while encrypting every tenant's secrets
        // under a value copied from .env.example is worse than one that refuses
        // to start.
        assertThatThrownBy(() -> cipher("CHANGE_ME_base64_encoded_32_byte_key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("startup fails on a key of the wrong length")
    void refusesShortKey() {
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("startup fails on a missing key")
    void refusesMissingKey() {
        assertThatThrownBy(() -> cipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set");
    }

    @Test
    @DisplayName("generated endpoint secrets are long, prefixed and unique")
    void generatesStrongSecrets() {
        SecretCipher cipher = cipher(validKey());
        Set<String> generated = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            String secret = cipher.generateEndpointSecret();
            assertThat(secret).startsWith("whsec_");
            // 32 random bytes, base64url-encoded.
            assertThat(secret.length()).isGreaterThanOrEqualTo(40);
            generated.add(secret);
        }
        assertThat(generated).hasSize(200);
    }

    @Test
    @DisplayName("null passes through unchanged")
    void handlesNull() {
        SecretCipher cipher = cipher(validKey());
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}

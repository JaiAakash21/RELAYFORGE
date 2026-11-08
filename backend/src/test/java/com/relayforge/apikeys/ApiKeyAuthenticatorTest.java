package com.relayforge.apikeys;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayforge.security.TokenHashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for API key parsing and hashing. */
class ApiKeyAuthenticatorTest {

    @Test
    @DisplayName("extracts the prefix from a well-formed key")
    void extractsPrefix() {
        assertThat(ApiKeyAuthenticator.extractPrefix("rf_live_abc12345_thesecretpart"))
                .isEqualTo("abc12345");
    }

    @Test
    @DisplayName("a secret containing underscores does not shift the prefix")
    void secretMayContainUnderscores() {
        // The secret is base64url and may legitimately contain '_' and '-'.
        // Splitting without a limit would shred it and read the prefix from the
        // wrong position — which is exactly the bug this guards against.
        assertThat(ApiKeyAuthenticator.extractPrefix("rf_live_abc12345_part_with_underscores"))
                .isEqualTo("abc12345");
        assertThat(ApiKeyAuthenticator.extractPrefix("rf_live_abc12345_a-b_c-d_e"))
                .isEqualTo("abc12345");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "notakey",
                "rf_live",
                "rf_live_",
                "rf_live_prefix_",
                "rf_live__secret",
                "xx_live_prefix_secret",
                "rf_live_prefix", // missing the secret segment
            })
    @DisplayName("malformed keys are rejected without a database lookup")
    void rejectsMalformed(String presented) {
        assertThat(ApiKeyAuthenticator.extractPrefix(presented)).isNull();
    }

    @Test
    @DisplayName("a null key is rejected")
    void rejectsNull() {
        assertThat(ApiKeyAuthenticator.extractPrefix(null)).isNull();
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void tolerantOfWhitespace() {
        // Copy-paste from a terminal or a config file routinely adds whitespace.
        assertThat(ApiKeyAuthenticator.extractPrefix("  rf_live_abc12345_secret  "))
                .isEqualTo("abc12345");
    }

    @Test
    @DisplayName("hashing is deterministic and collision-free across keys")
    void hashingIsStable() {
        String key = "rf_live_abc12345_secret";
        assertThat(TokenHashing.sha256(key)).isEqualTo(TokenHashing.sha256(key));
        assertThat(TokenHashing.sha256(key)).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(TokenHashing.sha256(key)).isNotEqualTo(TokenHashing.sha256(key + "x"));
    }

    @Test
    @DisplayName("hash comparison is exact")
    void matchesCompares() {
        String hash = TokenHashing.sha256("value");
        assertThat(TokenHashing.matches(hash, hash)).isTrue();
        assertThat(TokenHashing.matches(hash, TokenHashing.sha256("other"))).isFalse();
        assertThat(TokenHashing.matches(null, hash)).isFalse();
        assertThat(TokenHashing.matches(hash, null)).isFalse();
        // Length differences must not throw.
        assertThat(TokenHashing.matches("short", hash)).isFalse();
    }
}

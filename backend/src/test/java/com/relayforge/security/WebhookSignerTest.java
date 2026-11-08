package com.relayforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the webhook signature scheme.
 *
 * <p>These assert against a value computed independently rather than against
 * whatever the implementation happens to produce — a test that only checks
 * self-consistency would pass even if the algorithm were wrong, and integrators
 * implement this from the published documentation.
 */
class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    private static final String SECRET = "whsec_test_secret_value";
    private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant TIMESTAMP = Instant.ofEpochSecond(1_700_000_000L);

    /** HMAC-SHA256 computed independently of the class under test. */
    private static String expectedSignature(String secret, long epochSeconds, byte[] body)
            throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((epochSeconds + ".").getBytes(StandardCharsets.UTF_8));
        mac.update(body);
        return java.util.HexFormat.of().formatHex(mac.doFinal());
    }

    @Test
    @DisplayName("produces the documented t=,v1= header format")
    void producesDocumentedFormat() {
        String header = signer.sign(SECRET, TIMESTAMP, BODY);
        assertThat(header).matches("^t=1700000000,v1=[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("matches an independently computed HMAC over '<timestamp>.<body>'")
    void matchesIndependentComputation() throws Exception {
        String expected = expectedSignature(SECRET, 1_700_000_000L, BODY);
        assertThat(signer.hexSignature(SECRET, 1_700_000_000L, BODY)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the timestamp is inside the MAC, so it cannot be edited")
    void timestampIsCovered() {
        // If the timestamp were merely alongside the signature, an attacker could
        // change it to make an old capture look fresh. Different timestamp must
        // mean a different signature.
        String a = signer.hexSignature(SECRET, 1_700_000_000L, BODY);
        String b = signer.hexSignature(SECRET, 1_700_000_001L, BODY);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a different body produces a different signature")
    void bodyIsCovered() {
        String a = signer.hexSignature(SECRET, 1_700_000_000L, BODY);
        String b =
                signer.hexSignature(
                        SECRET, 1_700_000_000L, "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("verification accepts a signature this signer produced")
    void verifiesOwnSignature() {
        Instant now = Instant.now();
        String header = signer.sign(SECRET, now, BODY);
        assertThat(signer.verify(SECRET, header, BODY, now, 300)).isTrue();
    }

    @Test
    @DisplayName("verification rejects the wrong secret")
    void rejectsWrongSecret() {
        Instant now = Instant.now();
        String header = signer.sign(SECRET, now, BODY);
        assertThat(signer.verify("whsec_other", header, BODY, now, 300)).isFalse();
    }

    @Test
    @DisplayName("verification rejects a tampered body")
    void rejectsTamperedBody() {
        Instant now = Instant.now();
        String header = signer.sign(SECRET, now, BODY);
        assertThat(
                        signer.verify(
                                SECRET,
                                header,
                                "{\"id\":\"evt_tampered\"}".getBytes(StandardCharsets.UTF_8),
                                now,
                                300))
                .isFalse();
    }

    @Test
    @DisplayName("verification rejects a replay outside the tolerance window")
    void rejectsStaleTimestamp() {
        // Without this check, a captured request is replayable forever.
        Instant signedAt = Instant.now().minusSeconds(3600);
        String header = signer.sign(SECRET, signedAt, BODY);
        assertThat(signer.verify(SECRET, header, BODY, Instant.now(), 300)).isFalse();
        // Still valid when the window is wide enough.
        assertThat(signer.verify(SECRET, header, BODY, Instant.now(), 7200)).isTrue();
    }

    @Test
    @DisplayName("verification rejects a future timestamp beyond tolerance")
    void rejectsFutureTimestamp() {
        Instant signedAt = Instant.now().plusSeconds(3600);
        String header = signer.sign(SECRET, signedAt, BODY);
        assertThat(signer.verify(SECRET, header, BODY, Instant.now(), 300)).isFalse();
    }

    @Test
    @DisplayName("rotation emits both signatures, and either verifies")
    void rotationEmitsBothSignatures() {
        Instant now = Instant.now();
        String header = signer.signWithRotation("new_secret", "old_secret", now, BODY);

        assertThat(header.split("v1=", -1)).hasSize(3); // two v1= elements
        // A receiver mid-rollover matches on whichever secret it currently has.
        assertThat(signer.verify("new_secret", header, BODY, now, 300)).isTrue();
        assertThat(signer.verify("old_secret", header, BODY, now, 300)).isTrue();
        assertThat(signer.verify("unrelated_secret", header, BODY, now, 300)).isFalse();
    }

    @Test
    @DisplayName("rotation does not duplicate the element when the secret is unchanged")
    void rotationWithSameSecretEmitsOne() {
        Instant now = Instant.now();
        String header = signer.signWithRotation(SECRET, SECRET, now, BODY);
        assertThat(header.split("v1=", -1)).hasSize(2);
    }

    @Test
    @DisplayName("malformed headers are rejected rather than throwing")
    void rejectsMalformedHeaders() {
        Instant now = Instant.now();
        for (String header :
                new String[] {
                    "", "garbage", "t=notanumber,v1=abc", "v1=abc", "t=1700000000", "t=1700000000,v1=zz"
                }) {
            assertThat(signer.verify(SECRET, header, BODY, now, 300))
                    .as("header: %s", header)
                    .isFalse();
        }
        assertThat(signer.verify(SECRET, null, BODY, now, 300)).isFalse();
    }

    @Test
    @DisplayName("an empty body is signed consistently")
    void handlesEmptyBody() {
        Instant now = Instant.now();
        String header = signer.sign(SECRET, now, new byte[0]);
        assertThat(signer.verify(SECRET, header, new byte[0], now, 300)).isTrue();
    }
}

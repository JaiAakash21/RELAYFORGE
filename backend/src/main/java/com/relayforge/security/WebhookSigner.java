package com.relayforge.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Produces and verifies the {@code X-RelayForge-Signature} header.
 *
 * <h2>Scheme</h2>
 *
 * <pre>{@code
 * signed_payload = "<timestamp>.<raw request body>"
 * signature      = hex(HMAC-SHA256(endpoint_secret, signed_payload))
 * header         = "t=<timestamp>,v1=<signature>"
 * }</pre>
 *
 * <h2>Why the timestamp is inside the MAC, not beside it</h2>
 *
 * A signature over the body alone is replayable forever: anyone who captures one
 * valid request can resend it indefinitely and the receiver cannot tell. Binding
 * the timestamp into the signed material lets a receiver reject anything outside
 * a tolerance window, and because the timestamp is covered by the MAC it cannot
 * be edited to make an old capture look fresh.
 *
 * <h2>Why the version tag</h2>
 *
 * {@code v1=} lets a receiver accept several signature schemes at once, which is
 * what makes a future algorithm change deployable without a flag day. For the
 * same reason the header can carry several {@code v1=} values during a secret
 * rotation: one per valid secret, so the receiver matches whichever it has.
 *
 * <h2>Constant-time comparison</h2>
 *
 * {@link #verify} uses {@link MessageDigest#isEqual}. A naive {@code equals}
 * returns as soon as two bytes differ, and that timing difference is enough to
 * recover a valid signature byte by byte. This class is also the reference
 * implementation shipped in docs/API_DOCUMENTATION.md, so it has to model the
 * right thing for receivers to copy.
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    /** The signature header value for one secret. */
    public String sign(String secret, Instant timestamp, byte[] body) {
        long epochSeconds = timestamp.getEpochSecond();
        return "t=" + epochSeconds + ",v1=" + hexSignature(secret, epochSeconds, body);
    }

    /**
     * The signature header value during a secret rotation.
     *
     * <p>Emits one {@code v1=} element per valid secret so a receiver mid-rollout
     * can match on whichever it currently holds. Ordered current-first, because a
     * receiver that checks only the first element still works once it has
     * adopted the new secret.
     */
    public String signWithRotation(String currentSecret, String previousSecret, Instant timestamp, byte[] body) {
        long epochSeconds = timestamp.getEpochSecond();
        StringBuilder header = new StringBuilder("t=").append(epochSeconds);
        header.append(",v1=").append(hexSignature(currentSecret, epochSeconds, body));
        if (previousSecret != null && !previousSecret.equals(currentSecret)) {
            header.append(",v1=").append(hexSignature(previousSecret, epochSeconds, body));
        }
        return header.toString();
    }

    public String hexSignature(String secret, long epochSeconds, byte[] body) {
        return HEX.formatHex(rawSignature(secret, epochSeconds, body));
    }

    private byte[] rawSignature(String secret, long epochSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(Long.toString(epochSeconds).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body == null ? new byte[0] : body);
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }

    /**
     * Verifies a signature header the way a receiver should.
     *
     * <p>Provided so the project's own tests exercise exactly the algorithm the
     * published documentation tells integrators to implement — if the two ever
     * drift, a test fails rather than an integration silently breaking.
     *
     * @param toleranceSeconds how far the signed timestamp may be from now
     */
    public boolean verify(String secret, String header, byte[] body, Instant now, long toleranceSeconds) {
        if (header == null || header.isBlank()) {
            return false;
        }
        Long timestamp = null;
        boolean matched = false;
        for (String element : header.split(",")) {
            String[] pair = element.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if ("t".equals(pair[0])) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        if (timestamp == null) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }
        byte[] expected = rawSignature(secret, timestamp, body);
        for (String element : header.split(",")) {
            String[] pair = element.trim().split("=", 2);
            if (pair.length == 2 && "v1".equals(pair[0])) {
                byte[] presented;
                try {
                    presented = HEX.parseHex(pair[1]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                // Constant-time: do not short-circuit out of the loop on a match,
                // so the work done does not depend on which element matched.
                matched |= MessageDigest.isEqual(expected, presented);
            }
        }
        return matched;
    }
}

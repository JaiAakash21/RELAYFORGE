package com.relayforge.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for machine-generated bearer credentials — API keys and
 * refresh tokens.
 *
 * <h2>Why not BCrypt here</h2>
 *
 * BCrypt's cost factor exists to make guessing a <em>low-entropy</em> secret
 * expensive. The secrets hashed here are 256 bits of output from a CSPRNG: there
 * is no dictionary and no plausible brute force, so a deliberately slow hash
 * buys nothing. It does cost something: an API key is verified on every single
 * ingestion request, and a ~100 ms KDF there would cap throughput at a handful
 * of requests per second per core and make the platform trivially
 * self-DoS-able.
 *
 * <p>Passwords are a different problem and are hashed with BCrypt — see
 * {@code SecurityConfig#passwordEncoder}.
 *
 * <p>The hash is unsalted, deliberately. A salt would require fetching the row
 * before hashing, and the lookup is <em>by</em> hash. With full-entropy inputs a
 * salt adds nothing: identical hashes imply identical keys, which is already
 * impossible at 256 bits, and rainbow tables over a 2^256 space do not exist.
 */
public final class TokenHashing {

    private static final HexFormat HEX = HexFormat.of();

    private TokenHashing() {}

    /** Returns the lower-case hex SHA-256 digest of the token. */
    public static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /**
     * Constant-time comparison of two hex digests.
     *
     * <p>Used even though both values are already hashes: comparing with
     * {@code String.equals} leaks, through timing, how many leading characters of
     * a guessed key's hash were correct, which is enough to mount an incremental
     * attack against the lookup.
     */
    public static boolean matches(String presentedHash, String storedHash) {
        if (presentedHash == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedHash.getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}

package com.relayforge.common;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** Identifier and random-token helpers. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Ids() {}

    public static UUID newId() {
        return UUID.randomUUID();
    }

    /**
     * Generates a URL-safe random token.
     *
     * @param bytes entropy in bytes — 32 gives 256 bits, which is the floor for
     *              anything that acts as a bearer credential
     */
    public static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_ENCODER.encodeToString(buffer);
    }

    /**
     * Generates a random token from an unambiguous alphanumeric alphabet.
     *
     * <p>Base64url output contains {@code _} and {@code -}, which makes it unsafe
     * for any value that is later embedded in a delimited string and split back
     * out. Use this wherever the token has to survive that round trip — the API
     * key prefix being the case in point.
     *
     * <p>{@code 0}, {@code O}, {@code 1}, {@code l} and {@code I} are excluded so
     * a human reading a prefix off a screen cannot transcribe it wrongly.
     */
    public static String randomAlphanumeric(int length) {
        final String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Parses a UUID, turning a malformed one into a 404 rather than a 500. */
    public static UUID parse(String value, String resource) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotFoundException(resource, value);
        }
    }
}

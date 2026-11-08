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

    /** Parses a UUID, turning a malformed one into a 404 rather than a 500. */
    public static UUID parse(String value, String resource) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotFoundException(resource, value);
        }
    }
}

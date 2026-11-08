package com.relayforge.common;

/**
 * Bounded-string helpers.
 *
 * <p>Everything persisted from an outside source — a receiver's response body,
 * an exception message, a response header — goes through here first. The
 * database columns are sized deliberately, and a receiver must not be able to
 * grow our storage by returning a large body.
 */
public final class Truncation {

    public static final int RESPONSE_BODY_EXCERPT_LIMIT = 2048;
    public static final int ERROR_MESSAGE_LIMIT = 500;
    public static final int HEADERS_EXCERPT_LIMIT = 2048;

    private static final String SUFFIX = "…[truncated]";

    private Truncation() {}

    /** Truncates to {@code limit} characters, marking that truncation happened. */
    public static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        String normalised = value.replace('\u0000', ' ');
        if (normalised.length() <= limit) {
            return normalised;
        }
        int keep = Math.max(0, limit - SUFFIX.length());
        return normalised.substring(0, keep) + SUFFIX;
    }

    public static String responseBody(String value) {
        return truncate(value, RESPONSE_BODY_EXCERPT_LIMIT);
    }

    public static String errorMessage(String value) {
        return truncate(value, ERROR_MESSAGE_LIMIT);
    }

    public static String headers(String value) {
        return truncate(value, HEADERS_EXCERPT_LIMIT);
    }
}

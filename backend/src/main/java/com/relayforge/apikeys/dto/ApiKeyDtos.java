package com.relayforge.apikeys.dto;

import com.relayforge.apikeys.ApiKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for API keys. */
public final class ApiKeyDtos {

    private ApiKeyDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            /** Optional expiry. A key with no expiry is valid until revoked. */
            Instant expiresAt) {}

    /**
     * API key metadata.
     *
     * <p>Carries the {@code prefix} but never the hash. A hash is not a usable
     * credential, but displaying one invites someone to treat it as an
     * identifier, paste it into a config file, or compare it by hand — so it
     * simply never crosses the API boundary.
     */
    public record Response(
            UUID id,
            String name,
            String prefix,
            /** Masked display form, e.g. {@code rf_live_7fK2xQ_••••}. */
            String maskedKey,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt,
            String status) {

        public static Response from(ApiKey key) {
            return new Response(
                    key.getId(),
                    key.getName(),
                    key.getPrefix(),
                    "rf_live_" + key.getPrefix() + "_" + "•".repeat(12),
                    key.getCreatedAt(),
                    key.getExpiresAt(),
                    key.getRevokedAt(),
                    key.getLastUsedAt(),
                    status(key));
        }

        private static String status(ApiKey key) {
            if (key.getRevokedAt() != null) {
                return "REVOKED";
            }
            if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(Instant.now())) {
                return "EXPIRED";
            }
            return "ACTIVE";
        }
    }

    /** The create response — the only place a full key is ever returned. */
    public record CreatedResponse(Response apiKey, String key, String warning) {}
}

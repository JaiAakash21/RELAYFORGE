package com.relayforge.apikeys;

import com.relayforge.security.RelayForgePrincipal;
import com.relayforge.security.TokenHashing;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a presented API key into an authenticated principal.
 *
 * <h2>Key format</h2>
 *
 * <pre>{@code  rf_<env>_<prefix>_<secret>     e.g. rf_live_7fK2xQ_hT9...  }</pre>
 *
 * <p>The {@code prefix} segment is a short, non-secret identifier stored in the
 * clear. It gives an O(1) indexed lookup of the one candidate row, so
 * authentication is a single primary-key-style fetch plus one constant-time hash
 * comparison. The alternative — scanning for a matching hash — would either need
 * the full hash as the lookup key (fine) or a table scan (not fine); keeping an
 * explicit prefix also means the UI can show a user <em>which</em> key is which
 * without ever holding the secret.
 *
 * <h2>Revocation is immediate</h2>
 *
 * Unlike the JWT path, every API-key request validates against the database.
 * That is a deliberate asymmetry: API keys are long-lived credentials that live
 * in other people's CI systems, so "revoke now" has to mean now. The cost is one
 * indexed read per ingestion request, which is negligible next to the write the
 * request is about to perform.
 */
@Component
public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final ApiKeyRepository repository;

    public ApiKeyAuthenticator(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /** Why an API key was rejected. Never returned to the caller in detail. */
    public enum Rejection {
        MALFORMED,
        UNKNOWN,
        REVOKED,
        EXPIRED
    }

    public sealed interface Result {
        record Authenticated(RelayForgePrincipal principal, java.util.UUID apiKeyId)
                implements Result {}

        record Rejected(Rejection rejection) implements Result {}
    }

    /**
     * Verifies a presented key.
     *
     * <p>Read-only and non-transactional with respect to the caller: the
     * {@code last_used_at} touch happens separately so that a failure to record
     * usage statistics can never fail a valid request.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Result authenticate(String presented) {
        String prefix = extractPrefix(presented);
        if (prefix == null) {
            return new Result.Rejected(Rejection.MALFORMED);
        }

        Optional<ApiKey> found = repository.findByPrefix(prefix);
        if (found.isEmpty()) {
            return new Result.Rejected(Rejection.UNKNOWN);
        }
        ApiKey key = found.get();

        String presentedHash = TokenHashing.sha256(presented);
        if (!TokenHashing.matches(presentedHash, key.getKeyHash())) {
            // A correct prefix with a wrong secret: either a truncated copy-paste
            // or a guess. Logged without any part of the presented secret.
            log.debug("API key prefix {} was presented with a non-matching secret", prefix);
            return new Result.Rejected(Rejection.UNKNOWN);
        }

        Instant now = Instant.now();
        if (key.getRevokedAt() != null) {
            return new Result.Rejected(Rejection.REVOKED);
        }
        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now)) {
            return new Result.Rejected(Rejection.EXPIRED);
        }

        return new Result.Authenticated(
                RelayForgePrincipal.forApiKey(key.getTenantId(), key.getId()), key.getId());
    }

    /**
     * Records that a key was used.
     *
     * <p>In its own transaction, and failures are swallowed: "when was this key
     * last used?" is useful operational colour, not something worth rejecting a
     * valid ingestion over.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(java.util.UUID apiKeyId) {
        try {
            repository.touch(apiKeyId, Instant.now());
        } catch (RuntimeException e) {
            log.debug("Could not update last_used_at for API key {}", apiKeyId, e);
        }
    }

    /**
     * Extracts the lookup prefix from a presented key.
     *
     * <p>Returns {@code null} for anything that does not have the expected shape,
     * so a malformed credential costs one string split and never a database
     * query.
     */
    static String extractPrefix(String presented) {
        if (presented == null) {
            return null;
        }
        String trimmed = presented.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(ApiKeyService.KEY_PREFIX)) {
            return null;
        }
        String[] parts = trimmed.split("_");
        // rf _ env _ prefix _ secret
        if (parts.length != 4 || parts[2].isEmpty() || parts[3].isEmpty()) {
            return null;
        }
        return parts[2];
    }
}

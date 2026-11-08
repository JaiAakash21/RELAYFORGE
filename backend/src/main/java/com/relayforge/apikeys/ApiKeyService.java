package com.relayforge.apikeys;

import com.relayforge.common.Ids;
import com.relayforge.common.NotFoundException;
import com.relayforge.common.ValidationException;
import com.relayforge.security.TokenHashing;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, lists and revokes API keys.
 *
 * <p>The plaintext key exists only in the return value of {@link #create}. It is
 * never stored, never logged and cannot be recovered — if a user loses it they
 * must create a new one. That is the only design under which "we were breached"
 * does not also mean "every customer's ingestion credentials were breached".
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    static final String KEY_PREFIX = "rf";
    private static final String ENVIRONMENT_SEGMENT = "live";
    /** Characters in the non-secret lookup prefix. */
    private static final int PREFIX_LENGTH = 8;
    private static final int SECRET_BYTES = 32;

    private final ApiKeyRepository repository;

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * A newly created key.
     *
     * @param plaintext the full key — shown to the user exactly once
     */
    public record CreatedKey(ApiKey record, String plaintext) {}

    @Transactional
    public CreatedKey create(UUID tenantId, UUID createdBy, String name, Instant expiresAt) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("An API key name is required");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ValidationException("expiresAt must be in the future");
        }

        // Regenerate on the astronomically unlikely prefix collision rather than
        // failing the request: the prefix is only a lookup handle, and a unique
        // constraint violation here would be a confusing error for the user.
        String prefix;
        int attempts = 0;
        do {
            // Alphanumeric, not base64url: the prefix sits between '_'
            // delimiters in the key and must not contain one itself.
            prefix = Ids.randomAlphanumeric(PREFIX_LENGTH);
            attempts++;
            if (attempts > 5) {
                throw new IllegalStateException(
                        "Could not allocate a unique API key prefix after 5 attempts");
            }
        } while (repository.findByPrefix(prefix).isPresent());

        String secret = Ids.randomToken(SECRET_BYTES);
        String plaintext = KEY_PREFIX + "_" + ENVIRONMENT_SEGMENT + "_" + prefix + "_" + secret;

        ApiKey key =
                new ApiKey(
                        Ids.newId(),
                        tenantId,
                        name.trim(),
                        TokenHashing.sha256(plaintext),
                        prefix,
                        createdBy,
                        expiresAt);
        repository.save(key);

        // Logs the prefix only. The secret segment never reaches a log sink.
        log.info("Created API key {} (prefix {}) for tenant {}", key.getId(), prefix, tenantId);
        return new CreatedKey(key, plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Revokes a key.
     *
     * <p>Tenant-scoped lookup, so a key id belonging to another tenant is simply
     * not found. Idempotent: revoking an already-revoked key succeeds without
     * changing the original revocation timestamp, because a client retrying a
     * revocation should not be told it failed.
     */
    @Transactional
    public ApiKey revoke(UUID tenantId, UUID keyId) {
        ApiKey key =
                repository
                        .findByIdAndTenantId(keyId, tenantId)
                        .orElseThrow(() -> new NotFoundException("API key", keyId));
        key.revoke();
        repository.save(key);
        log.info("Revoked API key {} (prefix {}) for tenant {}", keyId, key.getPrefix(), tenantId);
        return key;
    }
}

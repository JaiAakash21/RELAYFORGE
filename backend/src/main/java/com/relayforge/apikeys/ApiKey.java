package com.relayforge.apikeys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A programmatic credential for event ingestion.
 *
 * <p>The plaintext key exists only in the HTTP response of the create call. What
 * is stored is a SHA-256 hash plus a non-secret {@code prefix}.
 *
 * <p>Why SHA-256 and not BCrypt, when passwords use BCrypt: an API key is 256
 * bits of machine-generated entropy, so there is no dictionary to attack and a
 * deliberately slow hash buys nothing — it only puts a ~100 ms CPU cost on every
 * single ingestion request. The threat BCrypt defends against (low-entropy
 * human-chosen secrets) does not apply here. See docs/SECURITY.md.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 32, unique = true)
    private String prefix;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() {}

    public ApiKey(
            UUID id,
            UUID tenantId,
            String name,
            String keyHash,
            String prefix,
            UUID createdBy,
            Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.keyHash = keyHash;
        this.prefix = prefix;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    public void touch(Instant when) {
        this.lastUsedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getPrefix() {
        return prefix;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    /** Deliberately omits the hash. */
    @Override
    public String toString() {
        return "ApiKey[id=" + id + ", prefix=" + prefix + "]";
    }
}

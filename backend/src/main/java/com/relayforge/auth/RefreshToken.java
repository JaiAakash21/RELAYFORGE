package com.relayforge.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A rotating refresh token, stored only as a SHA-256 hash.
 *
 * <p>Every refresh issues a new token and marks the old one as replaced. If a
 * token that has already been replaced is presented again, that is evidence the
 * token was captured, and the whole {@code familyId} is revoked at once rather
 * than just the single token. This is the standard
 * refresh-token-rotation-with-reuse-detection pattern; the lineage columns are
 * what make the detection possible.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    protected RefreshToken() {}

    public RefreshToken(
            UUID id,
            UUID userId,
            String tokenHash,
            UUID familyId,
            Instant expiresAt,
            String userAgent) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && replacedBy == null && expiresAt.isAfter(now);
    }

    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }

    public void replaceWith(UUID successorId) {
        this.replacedBy = successorId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public String getUserAgent() {
        return userAgent;
    }
}

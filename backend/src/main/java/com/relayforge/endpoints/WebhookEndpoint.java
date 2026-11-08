package com.relayforge.endpoints;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * A tenant-registered destination for webhook deliveries.
 *
 * <h2>Subscription matching</h2>
 *
 * {@code subscribedEventTypes} holds either exact event types
 * ({@code order.created}) or single-segment-tail wildcards ({@code order.*}). An
 * empty array means "every event type" — convenient for a catch-all debugging
 * endpoint, and explicit rather than implied by a null.
 *
 * <h2>Secrets</h2>
 *
 * {@code encryptedSecret} is an AES-256-GCM sealed blob, never a plaintext
 * secret. During rotation {@code previousEncryptedSecret} keeps the superseded
 * secret so that signatures produced with either are acceptable to a receiver
 * mid-rollout.
 */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 500)
    private String description;

    @Type(StringArrayType.class)
    @Column(name = "subscribed_event_types", columnDefinition = "text[]", nullable = false)
    private String[] subscribedEventTypes = new String[0];

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "text")
    private String encryptedSecret;

    @Column(name = "previous_encrypted_secret", columnDefinition = "text")
    private String previousEncryptedSecret;

    @Column(name = "secret_rotated_at")
    private Instant secretRotatedAt;

    @Column(name = "retry_max_attempts")
    private Integer retryMaxAttempts;

    @Column(name = "retry_initial_delay_ms")
    private Long retryInitialDelayMs;

    @Column(name = "retry_max_delay_ms")
    private Long retryMaxDelayMs;

    @Column(name = "retry_timeout_ms")
    private Integer retryTimeoutMs;

    @Column(name = "retry_jitter_factor")
    private Double retryJitterFactor;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "rate_limit_per_second")
    private Integer rateLimitPerSecond;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected WebhookEndpoint() {}

    public WebhookEndpoint(
            UUID id, UUID tenantId, String name, String url, String encryptedSecret) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.url = url;
        this.encryptedSecret = encryptedSecret;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * True when this endpoint should receive the given event type.
     *
     * <p>Kept on the entity so the rule has exactly one definition, used both by
     * the SQL-side candidate query and by in-memory verification of its result.
     */
    public boolean subscribesTo(String eventType) {
        if (subscribedEventTypes == null || subscribedEventTypes.length == 0) {
            return true;
        }
        for (String pattern : subscribedEventTypes) {
            if (matches(pattern, eventType)) {
                return true;
            }
        }
        return false;
    }

    /** Matches {@code a.b.c} against {@code a.b.c}, {@code a.b.*} or {@code *}. */
    public static boolean matches(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }
        if (pattern.equals("*") || pattern.equals(eventType)) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return eventType.startsWith(prefix);
        }
        return false;
    }

    /** True when deliveries may currently be dispatched to this endpoint. */
    public boolean isDeliverable() {
        return active && pausedAt == null && deletedAt == null;
    }

    public void pause() {
        if (pausedAt == null) {
            pausedAt = Instant.now();
        }
    }

    public void resume() {
        pausedAt = null;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.active = false;
    }

    /** Promotes a new sealed secret, retaining the current one for the grace window. */
    public void rotateSecret(String newEncryptedSecret) {
        this.previousEncryptedSecret = this.encryptedSecret;
        this.encryptedSecret = newEncryptedSecret;
        this.secretRotatedAt = Instant.now();
    }

    public void forgetPreviousSecret() {
        this.previousEncryptedSecret = null;
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

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<String> getSubscribedEventTypes() {
        return subscribedEventTypes == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(Arrays.asList(subscribedEventTypes));
    }

    public void setSubscribedEventTypes(Set<String> types) {
        this.subscribedEventTypes = types == null ? new String[0] : types.toArray(String[]::new);
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public String getPreviousEncryptedSecret() {
        return previousEncryptedSecret;
    }

    public Instant getSecretRotatedAt() {
        return secretRotatedAt;
    }

    public Integer getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(Integer v) {
        this.retryMaxAttempts = v;
    }

    public Long getRetryInitialDelayMs() {
        return retryInitialDelayMs;
    }

    public void setRetryInitialDelayMs(Long v) {
        this.retryInitialDelayMs = v;
    }

    public Long getRetryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public void setRetryMaxDelayMs(Long v) {
        this.retryMaxDelayMs = v;
    }

    public Integer getRetryTimeoutMs() {
        return retryTimeoutMs;
    }

    public void setRetryTimeoutMs(Integer v) {
        this.retryTimeoutMs = v;
    }

    public Double getRetryJitterFactor() {
        return retryJitterFactor;
    }

    public void setRetryJitterFactor(Double v) {
        this.retryJitterFactor = v;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Integer getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(Integer v) {
        this.rateLimitPerSecond = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public String toString() {
        return "WebhookEndpoint[id=" + id + ", name=" + name + ", url=" + url + "]";
    }
}

package com.relayforge.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Links a user to a tenant with a role. */
@Entity
@Table(name = "tenant_memberships")
public class TenantMembership {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TenantMembership() {}

    public TenantMembership(UUID id, UUID tenantId, UUID userId, TenantRole role) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public TenantRole getRole() {
        return role;
    }

    public void setRole(TenantRole role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.relayforge.security;

import com.relayforge.tenancy.TenantRole;
import java.util.UUID;

/**
 * The authenticated caller.
 *
 * <p>Deliberately carries the tenant, not just the identity. Almost every
 * authorisation question in this system is "may this caller act for this
 * tenant?", and resolving the tenant once at authentication time means no
 * service method has to look it up — or forget to.
 *
 * <p>An API-key caller has no {@code userId}: it is a credential belonging to a
 * tenant, not a person. Code that needs a human actor (replay auditing, for
 * example) must handle a null {@code userId} rather than assume one, which is
 * why it is a nullable field instead of a synthetic placeholder user.
 *
 * @param userId   the acting user, or {@code null} for an API-key caller
 * @param tenantId the tenant whose data this caller may touch
 * @param role     the caller's role within that tenant
 * @param authType how the caller authenticated
 * @param apiKeyId the API key used, or {@code null} for a user session
 */
public record RelayForgePrincipal(
        UUID userId, UUID tenantId, TenantRole role, AuthType authType, UUID apiKeyId) {

    public static RelayForgePrincipal forUser(UUID userId, UUID tenantId, TenantRole role) {
        return new RelayForgePrincipal(userId, tenantId, role, AuthType.JWT, null);
    }

    /**
     * An API-key caller.
     *
     * <p>Granted {@link TenantRole#MEMBER}: enough to ingest events and read the
     * tenant's own data, deliberately not enough to administer API keys. A
     * credential that can mint more credentials turns one leak into permanent
     * access.
     */
    public static RelayForgePrincipal forApiKey(UUID tenantId, UUID apiKeyId) {
        return new RelayForgePrincipal(null, tenantId, TenantRole.MEMBER, AuthType.API_KEY, apiKeyId);
    }

    public boolean isApiKey() {
        return authType == AuthType.API_KEY;
    }

    public boolean canWrite() {
        return role != null && role.canWrite();
    }

    public boolean canAdminister() {
        return role != null && role.canAdminister();
    }
}

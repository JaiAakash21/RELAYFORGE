package com.relayforge.security;

import com.relayforge.common.ForbiddenException;
import com.relayforge.tenancy.TenantRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single way application code obtains the current caller.
 *
 * <p>Centralised so that "who is calling and for which tenant?" has exactly one
 * answer and one place to audit. Controllers and services ask this component
 * rather than reaching into the {@code SecurityContextHolder} themselves, which
 * keeps the tenant-resolution rule from being reimplemented — slightly
 * differently — in a dozen places.
 */
@Component
public class CurrentPrincipal {

    /** @throws ForbiddenException when there is no authenticated caller */
    public RelayForgePrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof RelayForgeAuthentication relayForge
                && relayForge.isAuthenticated()) {
            return relayForge.getPrincipal();
        }
        throw new ForbiddenException("Authentication is required for this operation");
    }

    /** The tenant every query in this request must be scoped to. */
    public java.util.UUID tenantId() {
        return require().tenantId();
    }

    /** @throws ForbiddenException when the caller's role is insufficient */
    public RelayForgePrincipal requireRole(TenantRole minimum) {
        RelayForgePrincipal principal = require();
        if (principal.role() == null || !principal.role().atLeast(minimum)) {
            throw new ForbiddenException(
                    "This operation requires the " + minimum + " role or higher");
        }
        return principal;
    }

    /**
     * Requires a logged-in human.
     *
     * <p>Used for operations that must be attributable to a person — creating or
     * revoking API keys, changing tenant settings. An API key must not be able to
     * perform these: that would let a single leaked ingestion credential
     * escalate into persistent control of the tenant.
     */
    public RelayForgePrincipal requireUserSession() {
        RelayForgePrincipal principal = require();
        if (principal.isApiKey()) {
            throw new ForbiddenException(
                    "This operation requires a signed-in user and cannot be performed with an API"
                            + " key");
        }
        return principal;
    }
}

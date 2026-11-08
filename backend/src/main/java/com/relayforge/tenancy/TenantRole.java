package com.relayforge.tenancy;

/**
 * A member's role within one tenant.
 *
 * <p>Ordered from most to least privileged; {@link #atLeast} is the only
 * comparison the application performs, which keeps authorisation checks
 * readable at the call site.
 */
public enum TenantRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    /** True when this role is at least as privileged as {@code required}. */
    public boolean atLeast(TenantRole required) {
        return this.ordinal() <= required.ordinal();
    }

    public boolean canWrite() {
        return atLeast(MEMBER);
    }

    public boolean canAdminister() {
        return atLeast(ADMIN);
    }
}

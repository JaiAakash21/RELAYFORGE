package com.relayforge.security;

/** How the current caller proved who they are. */
public enum AuthType {
    /** A user session: a short-lived JWT issued after password login. */
    JWT,

    /** A programmatic credential belonging to a tenant, not to a person. */
    API_KEY
}

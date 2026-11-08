package com.relayforge.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security {@code Authentication} wrapping a {@link RelayForgePrincipal}.
 *
 * <p>Authorities are derived from the role and the authentication type, so URL
 * rules can express things like "a user session, not an API key" declaratively:
 * {@code SCOPE_SESSION} is granted only to JWT callers.
 */
public class RelayForgeAuthentication extends AbstractAuthenticationToken {

    private final RelayForgePrincipal principal;

    public RelayForgeAuthentication(RelayForgePrincipal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(RelayForgePrincipal principal) {
        List<GrantedAuthority> list = new java.util.ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
        list.add(
                new SimpleGrantedAuthority(
                        principal.isApiKey() ? "SCOPE_INGEST" : "SCOPE_SESSION"));
        return list;
    }

    @Override
    public RelayForgePrincipal getPrincipal() {
        return principal;
    }

    /** No credentials are retained after authentication — there is nothing to leak. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return principal.userId() != null
                ? principal.userId().toString()
                : "api-key:" + principal.apiKeyId();
    }
}

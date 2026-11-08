package com.relayforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayforge.apikeys.ApiKeyAuthenticator;
import com.relayforge.common.ApiError;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the caller's identity from either a JWT or an API key.
 *
 * <h2>Two credential types, one filter</h2>
 *
 * Both paths produce the same {@link RelayForgePrincipal}, so everything
 * downstream — services, repositories, authorisation checks — is written once
 * and does not care how the caller authenticated. Keeping the two in a single
 * filter also makes the precedence rule explicit and testable rather than an
 * accident of filter-chain ordering.
 *
 * <p>Precedence: a dedicated {@code X-API-Key} header wins, then
 * {@code Authorization: Bearer}, where the value's {@code rf_} prefix decides
 * whether it is an API key or a JWT. A caller who sends both gets the API key
 * interpretation, which is the least surprising reading of an explicit header.
 *
 * <h2>Failure behaviour</h2>
 *
 * A <em>present but invalid</em> credential short-circuits with 401 immediately.
 * An <em>absent</em> credential does not: the request proceeds unauthenticated
 * and the authorisation rules decide, which is what allows public endpoints
 * (login, health, OpenAPI) to live in the same chain. Distinguishing "no
 * credential" from "bad credential" is also what lets the API answer
 * {@code token_expired} so a browser knows to refresh rather than to log out.
 */
@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_KEY_VALUE_PREFIX = "rf_";

    /** Correlation keys published to the logging context for this request. */
    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_AUTH = "authType";

    private final JwtService jwtService;
    private final ApiKeyAuthenticator apiKeyAuthenticator;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public AuthenticationFilter(
            JwtService jwtService,
            ApiKeyAuthenticator apiKeyAuthenticator,
            ObjectMapper objectMapper,
            Tracer tracer) {
        this.jwtService = jwtService;
        this.apiKeyAuthenticator = apiKeyAuthenticator;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        try {
            if (apiKey != null && !apiKey.isBlank()) {
                if (!authenticateApiKey(apiKey.trim(), response)) {
                    return;
                }
            } else if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                String credential = authorization.substring(BEARER_PREFIX.length()).trim();
                if (credential.startsWith(API_KEY_VALUE_PREFIX)) {
                    if (!authenticateApiKey(credential, response)) {
                        return;
                    }
                } else if (!authenticateJwt(credential, response)) {
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            // Always cleared: a pooled request thread must not carry one tenant's
            // identity into the next request it serves.
            SecurityContextHolder.clearContext();
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_AUTH);
        }
    }

    /** @return {@code true} to continue the chain, {@code false} if a 401 was written */
    private boolean authenticateApiKey(String presented, HttpServletResponse response)
            throws IOException {
        ApiKeyAuthenticator.Result result = apiKeyAuthenticator.authenticate(presented);
        if (result instanceof ApiKeyAuthenticator.Result.Authenticated ok) {
            authenticate(ok.principal());
            apiKeyAuthenticator.recordUsage(ok.apiKeyId());
            return true;
        }
        ApiKeyAuthenticator.Rejection rejection =
                ((ApiKeyAuthenticator.Result.Rejected) result).rejection();
        // The codes distinguish revoked and expired because those are actionable
        // by the integrator; a wrong or unknown key gets a single generic answer
        // so the API cannot be used to confirm whether a guessed key exists.
        String code =
                switch (rejection) {
                    case REVOKED -> "api_key_revoked";
                    case EXPIRED -> "api_key_expired";
                    case MALFORMED, UNKNOWN -> "api_key_invalid";
                };
        String message =
                switch (rejection) {
                    case REVOKED -> "This API key has been revoked";
                    case EXPIRED -> "This API key has expired";
                    case MALFORMED, UNKNOWN -> "The provided API key is not valid";
                };
        writeUnauthorized(response, code, message);
        return false;
    }

    private boolean authenticateJwt(String token, HttpServletResponse response) throws IOException {
        JwtService.Verification verification = jwtService.verify(token);
        if (verification instanceof JwtService.Verification.Valid valid) {
            authenticate(valid.principal());
            return true;
        }
        if (verification instanceof JwtService.Verification.Expired) {
            writeUnauthorized(
                    response, "token_expired", "The access token has expired; refresh it and retry");
            return false;
        }
        writeUnauthorized(response, "token_invalid", "The access token is not valid");
        return false;
    }

    private void authenticate(RelayForgePrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new RelayForgeAuthentication(principal));
        // Every log line for the rest of this request carries the tenant, which
        // is what makes a multi-tenant log searchable during an incident.
        MDC.put(MDC_TENANT, String.valueOf(principal.tenantId()));
        MDC.put(MDC_AUTH, principal.authType().name());
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // WWW-Authenticate so standard HTTP clients behave predictably.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + code + "\"");
        objectMapper.writeValue(
                response.getOutputStream(), ApiError.of(code, message, currentTraceId()));
    }

    private String currentTraceId() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}

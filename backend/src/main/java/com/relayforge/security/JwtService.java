package com.relayforge.security;

import com.relayforge.config.RelayForgeProperties;
import com.relayforge.tenancy.TenantRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies access tokens.
 *
 * <h2>Token design, and what it deliberately does not do</h2>
 *
 * The access token is a short-lived (15 minutes by default) HS512 JWT carrying
 * the user, the active tenant and the role. It is self-contained: verifying it
 * needs no database round trip, which is what makes it cheap enough to check on
 * every request.
 *
 * <p>That self-containment has a consequence worth stating plainly: a JWT cannot
 * be revoked before it expires. Logging out, or removing a user from a tenant,
 * does not invalidate an already-issued access token. RelayForge handles this
 * the standard way rather than pretending otherwise:
 *
 * <ul>
 *   <li>the access token's life is kept short, so the window is bounded;
 *   <li>the refresh token <em>is</em> stateful (a hashed row that can be
 *       revoked), so logout and "sign out everywhere" take effect at the next
 *       refresh rather than never;
 *   <li>API keys, which are long-lived, are checked against the database on
 *       every single request and so are revocable immediately.
 * </ul>
 *
 * <p>Making access tokens stateful too would mean a database lookup per request
 * and would turn the token into a glorified session id. The 15-minute window is
 * the price of that; it is documented in docs/SECURITY.md rather than hidden.
 *
 * <h2>Why HS512</h2>
 *
 * There is a single issuer and a single verifier — this application — so a
 * symmetric MAC is the right tool; an asymmetric key pair would add key
 * distribution for no benefit. The secret is required to carry at least 512 bits
 * of entropy and the application refuses to start otherwise, because HS512 with
 * a short secret is brute-forceable offline.
 */
@Component
public class JwtService {

    private static final String CLAIM_TENANT = "tid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";

    private final SecretKey key;
    private final Duration accessTtl;
    private final String issuer;

    public JwtService(RelayForgeProperties properties) {
        RelayForgeProperties.Jwt cfg = properties.jwt();
        this.key = buildKey(cfg.secret());
        this.accessTtl = cfg.accessTtl();
        this.issuer = cfg.issuer();
    }

    /** Builds the signing key, refusing weak or placeholder material. */
    private static SecretKey buildKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "relayforge.jwt.secret is not set. Generate one with: openssl rand -base64 64");
        }
        if (secret.startsWith("CHANGE_ME")) {
            throw new IllegalStateException(
                    "relayforge.jwt.secret still holds the placeholder value from .env.example."
                            + " Generate a real secret with: openssl rand -base64 64");
        }
        byte[] material;
        try {
            byte[] decoded = Base64.getDecoder().decode(secret.trim());
            // Prefer the decoded bytes only if decoding actually produced more
            // entropy than treating the string as raw text would.
            material = decoded.length >= 64 ? decoded : secret.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            material = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (material.length < 64) {
            throw new IllegalStateException(
                    "relayforge.jwt.secret must provide at least 64 bytes (512 bits) of entropy for"
                            + " HS512; got "
                            + material.length
                            + " bytes. Generate one with: openssl rand -base64 64");
        }
        return Keys.hmacShaKeyFor(material);
    }

    /** Issues an access token for a user acting on behalf of one tenant. */
    public String issueAccessToken(UUID userId, UUID tenantId, TenantRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .id(UUID.randomUUID().toString())
                .claims(
                        Map.of(
                                CLAIM_TENANT, tenantId.toString(),
                                CLAIM_ROLE, role.name(),
                                CLAIM_TYPE, TYPE_ACCESS))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    /** The result of verifying a token. */
    public sealed interface Verification {
        record Valid(RelayForgePrincipal principal) implements Verification {}

        record Expired() implements Verification {}

        record Invalid(String reason) implements Verification {}
    }

    /**
     * Verifies a token and extracts the principal.
     *
     * <p>Returns a result rather than throwing, because an expired token is a
     * normal, expected event that the client handles by refreshing — not an
     * exceptional condition. Distinguishing expiry from invalidity lets the API
     * tell a client "refresh and retry" instead of "your credentials are wrong".
     */
    public Verification verify(String token) {
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(key)
                            .requireIssuer(issuer)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                return new Verification.Invalid("not an access token");
            }
            String tenant = claims.get(CLAIM_TENANT, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            if (tenant == null || role == null) {
                return new Verification.Invalid("token is missing tenant or role claims");
            }
            return new Verification.Valid(
                    RelayForgePrincipal.forUser(
                            UUID.fromString(claims.getSubject()),
                            UUID.fromString(tenant),
                            TenantRole.valueOf(role)));
        } catch (ExpiredJwtException e) {
            return new Verification.Expired();
        } catch (JwtException | IllegalArgumentException e) {
            // The reason is intentionally generic: telling a caller precisely why
            // a signature failed is free information for someone forging tokens.
            return new Verification.Invalid("token is not valid");
        }
    }
}

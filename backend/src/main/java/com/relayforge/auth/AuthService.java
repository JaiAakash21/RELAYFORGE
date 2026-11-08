package com.relayforge.auth;

import com.relayforge.auth.dto.AuthDtos;
import com.relayforge.common.ConflictException;
import com.relayforge.common.Ids;
import com.relayforge.common.NotFoundException;
import com.relayforge.common.RelayForgeException;
import com.relayforge.common.ValidationException;
import com.relayforge.security.JwtService;
import com.relayforge.security.TokenHashing;
import com.relayforge.tenancy.Tenant;
import com.relayforge.tenancy.TenantMembership;
import com.relayforge.tenancy.TenantMembershipRepository;
import com.relayforge.tenancy.TenantRepository;
import com.relayforge.tenancy.TenantRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, login, refresh-token rotation and logout. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A BCrypt hash of a throwaway value, used to equalise timing on the
     * unknown-user path. See {@link #login}.
     */
    private static final String DUMMY_HASH =
            "$2a$12$usesomesillystringfooWqWbqFJGZ.ZQ6FAoFNiJ8DQeKGu8Hy";

    private final UserRepository users;
    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final java.time.Duration refreshTtl;

    public AuthService(
            UserRepository users,
            TenantRepository tenants,
            TenantMembershipRepository memberships,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            com.relayforge.config.RelayForgeProperties properties) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTtl = properties.jwt().refreshTtl();
    }

    /**
     * Registers a user and the tenant they own.
     *
     * <p>Self-service registration creates a tenant too: a user with no tenant
     * could not do anything, and making tenant creation a separate step would
     * leave a half-initialised account on any failure between the two.
     */
    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request, String userAgent) {
        String email = normaliseEmail(request.email());
        if (users.existsByEmail(email)) {
            // Registration is the one place where disclosing that an account
            // exists is unavoidable — the alternative is silently doing nothing
            // and leaving the user unable to proceed. Login does not disclose it.
            throw new ConflictException("email_taken", "An account with this email already exists");
        }

        User user =
                new User(
                        Ids.newId(),
                        email,
                        passwordEncoder.encode(request.password()),
                        blankToNull(request.displayName()));
        users.save(user);

        String tenantName =
                Optional.ofNullable(blankToNull(request.organizationName()))
                        .orElseGet(() -> defaultTenantName(email));
        Tenant tenant = new Tenant(Ids.newId(), tenantName, allocateSlug(tenantName));
        tenants.save(tenant);

        memberships.save(
                new TenantMembership(
                        Ids.newId(), tenant.getId(), user.getId(), TenantRole.OWNER));

        log.info("Registered user {} with tenant {}", user.getId(), tenant.getId());
        return issueTokens(user, tenant, TenantRole.OWNER, userAgent);
    }

    /**
     * Authenticates a user.
     *
     * <h2>Why the dummy hash</h2>
     *
     * If an unknown email returned immediately while a known one spent 250 ms in
     * BCrypt, the response time alone would reveal which addresses have accounts
     * — a free user-enumeration oracle. Verifying against a throwaway hash makes
     * both paths cost the same, and both return the same generic error.
     */
    @Transactional
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request, String userAgent) {
        String email = normaliseEmail(request.email());
        Optional<User> found = users.findByEmail(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw invalidCredentials();
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<TenantMembership> userMemberships = memberships.findByUserId(user.getId());
        if (userMemberships.isEmpty()) {
            throw new RelayForgeException(
                    HttpStatus.FORBIDDEN,
                    "no_tenant",
                    "This account is not a member of any organization");
        }
        TenantMembership active = userMemberships.getFirst();
        Tenant tenant =
                tenants.findById(active.getTenantId())
                        .orElseThrow(() -> new NotFoundException("Tenant", active.getTenantId()));

        user.markLoggedIn();
        users.save(user);
        return issueTokens(user, tenant, active.getRole(), userAgent);
    }

    /**
     * Rotates a refresh token.
     *
     * <h2>Reuse detection</h2>
     *
     * Each refresh issues a new token and marks the presented one as replaced. If
     * a token that was already replaced comes back, two parties hold it — the
     * legitimate client and whoever captured it. There is no way to tell which is
     * which, so the entire token family is revoked and both are forced to log in
     * again. Losing a session is a small cost next to leaving a stolen token
     * usable for its full lifetime.
     */
    @Transactional
    public AuthDtos.TokenResponse refresh(String presentedToken, String userAgent) {
        String hash = TokenHashing.sha256(presentedToken);
        RefreshToken stored =
                refreshTokens.findByTokenHash(hash).orElseThrow(this::invalidRefreshToken);

        Instant now = Instant.now();
        if (stored.getReplacedBy() != null) {
            int revoked = refreshTokens.revokeFamily(stored.getFamilyId(), now);
            log.warn(
                    "Refresh token reuse detected for user {}; revoked {} tokens in family {}",
                    stored.getUserId(),
                    revoked,
                    stored.getFamilyId());
            throw invalidRefreshToken();
        }
        if (!stored.isUsable(now)) {
            throw invalidRefreshToken();
        }

        User user =
                users.findById(stored.getUserId())
                        .orElseThrow(() -> new NotFoundException("User", stored.getUserId()));
        List<TenantMembership> userMemberships = memberships.findByUserId(user.getId());
        if (userMemberships.isEmpty()) {
            throw new RelayForgeException(
                    HttpStatus.FORBIDDEN, "no_tenant", "This account has no organization");
        }
        TenantMembership active = userMemberships.getFirst();
        Tenant tenant =
                tenants.findById(active.getTenantId())
                        .orElseThrow(() -> new NotFoundException("Tenant", active.getTenantId()));

        // Issue the successor first so its id can be recorded on the predecessor,
        // which is what makes the lineage — and therefore the reuse detection —
        // reliable.
        AuthDtos.TokenResponse response =
                issueTokensInFamily(user, tenant, active.getRole(), userAgent, stored.getFamilyId());
        RefreshToken successor =
                refreshTokens
                        .findByTokenHash(TokenHashing.sha256(response.refreshToken()))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Newly issued refresh token could not be read back"));
        stored.replaceWith(successor.getId());
        stored.revoke();
        refreshTokens.save(stored);
        return response;
    }

    /**
     * Logs out.
     *
     * <p>Revokes the whole family rather than one token, so "log out" means the
     * device really is logged out and cannot silently resume by presenting an
     * older token from the same chain. Unknown or already-revoked tokens succeed
     * silently: logout must be idempotent, and reporting "that token does not
     * exist" would be an enumeration oracle.
     */
    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        refreshTokens
                .findByTokenHash(TokenHashing.sha256(presentedToken))
                .ifPresent(
                        token -> refreshTokens.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    /** Revokes every refresh token for a user — "sign out everywhere". */
    @Transactional
    public int logoutEverywhere(UUID userId) {
        return refreshTokens.revokeAllForUser(userId, Instant.now());
    }

    @Transactional(readOnly = true)
    public AuthDtos.SessionResponse currentSession(UUID userId, UUID tenantId) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        List<AuthDtos.TenantResponse> available =
                memberships.findByUserId(userId).stream()
                        .map(
                                membership ->
                                        tenants.findById(membership.getTenantId())
                                                .map(
                                                        tenant ->
                                                                new AuthDtos.TenantResponse(
                                                                        tenant.getId(),
                                                                        tenant.getName(),
                                                                        tenant.getSlug(),
                                                                        membership.getRole()))
                                                .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        AuthDtos.TenantResponse activeTenant =
                available.stream()
                        .filter(t -> t.id().equals(tenantId))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("Tenant", tenantId));
        return new AuthDtos.SessionResponse(toUserResponse(user), activeTenant, available);
    }

    // --------------------------------------------------------------- internals

    private AuthDtos.TokenResponse issueTokens(
            User user, Tenant tenant, TenantRole role, String userAgent) {
        return issueTokensInFamily(user, tenant, role, userAgent, Ids.newId());
    }

    private AuthDtos.TokenResponse issueTokensInFamily(
            User user, Tenant tenant, TenantRole role, String userAgent, UUID familyId) {
        String accessToken = jwtService.issueAccessToken(user.getId(), tenant.getId(), role);
        String refreshTokenValue = Ids.randomToken(32);

        refreshTokens.save(
                new RefreshToken(
                        Ids.newId(),
                        user.getId(),
                        TokenHashing.sha256(refreshTokenValue),
                        familyId,
                        Instant.now().plus(refreshTtl),
                        truncateUserAgent(userAgent)));

        List<AuthDtos.TenantResponse> available =
                memberships.findByUserId(user.getId()).stream()
                        .map(
                                membership ->
                                        tenants.findById(membership.getTenantId())
                                                .map(
                                                        t ->
                                                                new AuthDtos.TenantResponse(
                                                                        t.getId(),
                                                                        t.getName(),
                                                                        t.getSlug(),
                                                                        membership.getRole()))
                                                .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();

        return new AuthDtos.TokenResponse(
                accessToken,
                refreshTokenValue,
                "Bearer",
                jwtService.accessTtl().toSeconds(),
                toUserResponse(user),
                new AuthDtos.TenantResponse(
                        tenant.getId(), tenant.getName(), tenant.getSlug(), role),
                available);
    }

    private static AuthDtos.UserResponse toUserResponse(User user) {
        return new AuthDtos.UserResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    private RelayForgeException invalidCredentials() {
        // One message for "no such user" and "wrong password", on purpose.
        return new RelayForgeException(
                HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect");
    }

    private RelayForgeException invalidRefreshToken() {
        return new RelayForgeException(
                HttpStatus.UNAUTHORIZED,
                "invalid_refresh_token",
                "The refresh token is not valid; sign in again");
    }

    private static String normaliseEmail(String email) {
        if (email == null) {
            throw new ValidationException("An email address is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultTenantName(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return local.isBlank() ? "My organization" : local + "'s organization";
    }

    /** Derives a unique slug, appending a short random suffix on collision. */
    private String allocateSlug(String name) {
        String base =
                name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "tenant";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        if (!tenants.existsBySlug(base)) {
            return base;
        }
        for (int i = 0; i < 5; i++) {
            String candidate = base + "-" + Ids.randomToken(3).toLowerCase(Locale.ROOT);
            if (!tenants.existsBySlug(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String truncateUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent;
    }
}

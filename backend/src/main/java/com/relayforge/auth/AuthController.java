package com.relayforge.auth;

import com.relayforge.auth.dto.AuthDtos;
import com.relayforge.common.ValidationException;
import com.relayforge.ratelimit.RateLimiter;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login and token lifecycle")
public class AuthController {

    private final AuthService authService;
    private final CurrentPrincipal currentPrincipal;
    private final RateLimiter rateLimiter;

    public AuthController(
            AuthService authService, CurrentPrincipal currentPrincipal, RateLimiter rateLimiter) {
        this.authService = authService;
        this.currentPrincipal = currentPrincipal;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account and its organization")
    public ResponseEntity<AuthDtos.TokenResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request, HttpServletRequest http) {
        rateLimiter.checkAuthAttempt(clientKey(http));
        AuthDtos.TokenResponse response =
                authService.register(request, http.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access and refresh token")
    public AuthDtos.TokenResponse login(
            @Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest http) {
        // Rate-limited per client before the password is checked, so credential
        // stuffing is throttled rather than merely logged.
        rateLimiter.checkAuthAttempt(clientKey(http));
        return authService.login(request, http.getHeader(HttpHeaders.USER_AGENT));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token for a new access token")
    public AuthDtos.TokenResponse refresh(
            @Valid @RequestBody AuthDtos.RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(
                request.refreshToken(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the presented refresh token and its rotation family")
    public ResponseEntity<Void> logout(@RequestBody(required = false) AuthDtos.RefreshRequest request) {
        if (request != null) {
            authService.logout(request.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every refresh token for the signed-in user")
    public ResponseEntity<Void> logoutEverywhere() {
        RelayForgePrincipal principal = currentPrincipal.requireUserSession();
        authService.logoutEverywhere(principal.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    @Operation(summary = "Describe the current session, user and available organizations")
    public AuthDtos.SessionResponse session() {
        RelayForgePrincipal principal = currentPrincipal.requireUserSession();
        return authService.currentSession(principal.userId(), principal.tenantId());
    }

    /**
     * Identifies the client for auth rate limiting.
     *
     * <p>Uses the leftmost {@code X-Forwarded-For} entry when present, falling
     * back to the socket address. This is advisory: a client can forge the header,
     * so it buys throttling of unsophisticated credential stuffing, not a
     * security boundary. A real deployment should have the edge proxy overwrite
     * the header — noted in docs/SECURITY.md.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            throw new ValidationException("Unable to identify the calling client");
        }
        return remote;
    }
}

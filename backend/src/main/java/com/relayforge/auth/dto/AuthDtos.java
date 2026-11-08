package com.relayforge.auth.dto;

import com.relayforge.tenancy.TenantRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for authentication.
 *
 * <p>Grouped in one file because they are a single cohesive contract and are
 * always read together; splitting eleven five-line records across eleven files
 * would add navigation cost without adding clarity.
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            /**
             * Minimum 12 characters, no composition rules.
             *
             * <p>Length is what actually resists guessing; mandatory symbol
             * classes mostly produce {@code Password1!} and are discouraged by
             * current NIST guidance. The maximum exists because BCrypt silently
             * ignores input beyond 72 bytes — accepting a 200-character password
             * and then truncating it would be a lie about the security provided.
             */
            @NotBlank @Size(min = 12, max = 72) String password,
            @Size(max = 120) String displayName,
            @Size(max = 120) String organizationName) {}

    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    /** Issued credentials. The refresh token is returned once per rotation. */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            UserResponse user,
            TenantResponse tenant,
            List<TenantResponse> availableTenants) {}

    public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {}

    public record TenantResponse(UUID id, String name, String slug, TenantRole role) {}

    public record SessionResponse(
            UserResponse user, TenantResponse tenant, List<TenantResponse> availableTenants) {}
}

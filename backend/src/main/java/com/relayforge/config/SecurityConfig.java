package com.relayforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayforge.common.ApiError;
import com.relayforge.security.AuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Web security: CORS, the filter chain, authorisation rules and password hashing. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt at cost 12 for user passwords.
     *
     * <p>Passwords are low-entropy and human-chosen, so the deliberate slowness
     * is the entire point: it is what makes an offline attack on a stolen hash
     * table expensive. Cost 12 is roughly 250 ms on current hardware — slow
     * enough to matter to an attacker testing billions of candidates, fast
     * enough that a login feels instant.
     *
     * <p>This is the opposite trade-off from
     * {@link com.relayforge.security.TokenHashing}, and for a good reason: that
     * class hashes 256-bit random values, where slowness protects nothing and
     * costs throughput on every request.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationFilter authenticationFilter,
            ObjectMapper objectMapper,
            CorsConfigurationSource corsConfigurationSource)
            throws Exception {

        http
                // No server-side session: both credential types are per-request
                // bearer credentials, so a session would be dead weight and an
                // extra piece of state to fix across horizontally scaled API pods.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF protection defends browser requests that authenticate with
                // an ambient cookie. This API never does: it requires an explicit
                // Authorization or X-API-Key header, which a cross-site form
                // cannot set. The browser-facing cookie lives in the Next.js BFF,
                // which is where the SameSite protection is applied.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .headers(
                        headers ->
                                headers
                                        .frameOptions(frame -> frame.deny())
                                        .contentTypeOptions(Customizer.withDefaults())
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                org.springframework.security.web
                                                                        .header.writers
                                                                        .ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .NO_REFERRER)))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // Pre-flight must not require a credential.
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        // Public: registration, login and refresh.
                                        // Refresh is public because the refresh
                                        // cookie *is* the credential it checks.
                                        .requestMatchers(
                                                "/api/v1/auth/register",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/refresh")
                                        .permitAll()
                                        // Liveness and readiness are consumed by
                                        // Docker/Kubernetes before any credential
                                        // exists. Everything else under /actuator
                                        // (including /prometheus) stays protected.
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info")
                                        .permitAll()
                                        // API documentation is public so an
                                        // integrator can read the contract before
                                        // they have a key.
                                        .requestMatchers(
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**")
                                        .permitAll()
                                        // Event ingestion: an API key suffices, and
                                        // is the expected credential.
                                        .requestMatchers(HttpMethod.POST, "/api/v1/events")
                                        .authenticated()
                                        // Credential and tenant administration is
                                        // restricted to real user sessions. The
                                        // service layer re-checks; this is the
                                        // coarse first gate.
                                        .requestMatchers("/api/v1/api-keys/**")
                                        .hasAuthority("SCOPE_SESSION")
                                        .requestMatchers("/api/v1/settings/**")
                                        .hasAuthority("SCOPE_SESSION")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                (request, response, authException) ->
                                                        writeError(
                                                                response,
                                                                objectMapper,
                                                                HttpServletResponse.SC_UNAUTHORIZED,
                                                                "unauthenticated",
                                                                "Authentication is required"))
                                        .accessDeniedHandler(
                                                (request, response, deniedException) ->
                                                        writeError(
                                                                response,
                                                                objectMapper,
                                                                HttpServletResponse.SC_FORBIDDEN,
                                                                "forbidden",
                                                                "You do not have permission to"
                                                                        + " perform this action")))
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message)
            throws java.io.IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message, null));
    }

    /**
     * CORS, restricted to configured origins.
     *
     * <p>Origins come from configuration and are never wildcarded, because
     * {@code allowCredentials(true)} with {@code *} is both forbidden by the spec
     * and exactly the mistake that makes an API readable by any website the user
     * happens to visit.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(RelayForgeProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of(
                        HttpHeaders.AUTHORIZATION,
                        HttpHeaders.CONTENT_TYPE,
                        HttpHeaders.ACCEPT,
                        "X-API-Key",
                        "Idempotency-Key",
                        "traceparent"));
        configuration.setExposedHeaders(
                List.of("X-Request-Id", HttpHeaders.RETRY_AFTER, "X-RateLimit-Remaining"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}

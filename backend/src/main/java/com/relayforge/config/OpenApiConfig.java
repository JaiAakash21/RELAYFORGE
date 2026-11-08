package com.relayforge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document describing both credential types. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI relayForgeOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("RelayForge API")
                                .version("1.0.0")
                                .description(
                                        """
                                        Reliable multi-tenant webhook delivery.

                                        ## Authentication

                                        Two credential types, for two different callers:

                                        * **API key** (`X-API-Key`, or `Authorization: Bearer rf_…`)
                                          — for servers publishing events. Long-lived, validated
                                          against the database on every request, so revocation is
                                          immediate.
                                        * **Access token** (`Authorization: Bearer <jwt>`) — for the
                                          dashboard. Short-lived and self-contained; refresh it with
                                          `POST /api/v1/auth/refresh`.

                                        An API key can publish events and read its tenant's data. It
                                        deliberately cannot manage API keys or tenant settings — a
                                        leaked ingestion credential must not be able to grant itself
                                        permanent access.

                                        ## Delivery semantics

                                        Delivery is **at-least-once**. A delivery is retried with
                                        exponential backoff and jitter until it succeeds or its
                                        attempt budget is exhausted, and a worker that dies after
                                        sending a request but before recording the result will cause
                                        that request to be sent again. Every request carries a stable
                                        `X-RelayForge-Event-Id`; deduplicate on it.

                                        ## Verifying signatures

                                        Each request carries:

                                        ```
                                        X-RelayForge-Signature: t=<unix-seconds>,v1=<hex hmac>
                                        ```

                                        where the signature is
                                        `HMAC-SHA256(secret, "<t>.<raw body>")`. Compare in constant
                                        time and reject timestamps outside a tolerance window —
                                        otherwise a captured request is replayable forever. Several
                                        `v1=` values appear during a secret rotation; accept the
                                        request if any one matches your secret.
                                        """)
                                .contact(new Contact().name("RelayForge").url("https://github.com/JaiAakash21/RELAYFORGE"))
                                .license(new License().name("MIT")))
                .servers(
                        List.of(
                                new Server().url("/").description("This instance"),
                                new Server()
                                        .url("http://localhost:8080")
                                        .description("Local development")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "apiKey",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-API-Key")
                                                .description(
                                                        "A tenant API key. Shown in full only when"
                                                                + " created."))
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "A dashboard access token from"
                                                                + " /auth/login.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}

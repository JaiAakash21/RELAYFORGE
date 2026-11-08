package com.relayforge.config;

import com.relayforge.apikeys.ApiKeyService;
import com.relayforge.auth.AuthService;
import com.relayforge.auth.UserRepository;
import com.relayforge.auth.dto.AuthDtos;
import com.relayforge.endpoints.EndpointService;
import com.relayforge.endpoints.dto.EndpointDtos;
import com.relayforge.tenancy.TenantMembershipRepository;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates a demo account on first start, when explicitly enabled.
 *
 * <h2>Guard rails</h2>
 *
 * This runs only when {@code relayforge.demo.seed-enabled} is {@code true}, which
 * is {@code false} by default and set only in the local Compose file. A seeded
 * account with a known password is a backdoor, so it must never be something
 * that happens by accident in an environment nobody intended it for.
 *
 * <p>The seeded data is also clearly labelled: events it creates carry the
 * {@code SEED} source, so demo traffic is distinguishable from real traffic in
 * every list and metric rather than quietly inflating them.
 */
@Component
@ConditionalOnProperty(name = "relayforge.demo.seed-enabled", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final RelayForgeProperties properties;
    private final AuthService authService;
    private final UserRepository users;
    private final TenantMembershipRepository memberships;
    private final EndpointService endpointService;
    private final ApiKeyService apiKeyService;

    public DemoSeeder(
            RelayForgeProperties properties,
            AuthService authService,
            UserRepository users,
            TenantMembershipRepository memberships,
            EndpointService endpointService,
            ApiKeyService apiKeyService) {
        this.properties = properties;
        this.authService = authService;
        this.users = users;
        this.memberships = memberships;
        this.endpointService = endpointService;
        this.apiKeyService = apiKeyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        RelayForgeProperties.Demo demo = properties.demo();
        if (demo.email() == null || demo.password() == null) {
            log.warn(
                    "Demo seeding is enabled but relayforge.demo.email/password are not set;"
                            + " skipping.");
            return;
        }
        if (demo.password().startsWith("CHANGE_ME")) {
            log.warn(
                    "Demo seeding is enabled but the demo password is still the .env.example"
                            + " placeholder; skipping rather than creating an account with a known"
                            + " default password.");
            return;
        }

        String email = demo.email().toLowerCase(java.util.Locale.ROOT);
        if (users.existsByEmail(email)) {
            log.info("Demo account {} already exists; nothing to seed.", email);
            return;
        }

        AuthDtos.TokenResponse registration =
                authService.register(
                        new AuthDtos.RegisterRequest(
                                email,
                                demo.password(),
                                "Demo User",
                                demo.tenantName() == null ? "RelayForge Demo" : demo.tenantName()),
                        "demo-seeder");

        UUID tenantId = registration.tenant().id();
        UUID userId = registration.user().id();

        // A catch-all endpoint pointed at the simulator's always-succeeding
        // target, so a fresh install has something that works before the user
        // configures anything.
        endpointService.create(
                tenantId,
                new EndpointDtos.CreateRequest(
                        "Demo receiver (always succeeds)",
                        properties.playground().simulatorBaseUrl()
                                + "/simulator/receive/demo-success",
                        "Seeded demo endpoint pointing at the local webhook simulator.",
                        Set.of("order.*", "user.*"),
                        5,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        ApiKeyService.CreatedKey key =
                apiKeyService.create(tenantId, userId, "Demo ingestion key", null);

        // Logged at startup on purpose: this is a local development convenience
        // and the operator needs the key. It is only reachable at all because
        // seeding was explicitly enabled.
        log.info(
                """

                ===========================================================================
                 RelayForge demo environment seeded
                ---------------------------------------------------------------------------
                 Sign in at the dashboard with:
                   email    : {}
                   password : (the value of RELAYFORGE_DEMO_PASSWORD)

                 Demo ingestion API key (shown once, not recoverable):
                   {}

                 Publish an event:
                   curl -X POST http://localhost:8080/api/v1/events \\
                     -H "X-API-Key: {}" \\
                     -H "Content-Type: application/json" \\
                     -d '{{"eventType":"order.created","payload":{{"orderId":"ORD-1001"}}}}'
                ===========================================================================
                """,
                email,
                key.plaintext(),
                key.plaintext());
    }
}

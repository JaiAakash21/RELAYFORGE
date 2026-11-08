package com.relayforge.endpoints;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for subscription matching and deliverability.
 *
 * <p>Subscription matching decides which endpoints receive an event, so a bug
 * here is either a missed delivery or an unwanted one. The rule has a single
 * definition on the entity, and this is where that definition is pinned.
 */
class WebhookEndpointTest {

    private static WebhookEndpoint endpoint(Set<String> subscriptions) {
        WebhookEndpoint endpoint =
                new WebhookEndpoint(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "test",
                        "https://example.com/hook",
                        "sealed");
        endpoint.setSubscribedEventTypes(subscriptions);
        return endpoint;
    }

    @ParameterizedTest(name = "pattern {0} vs {1} -> {2}")
    @CsvSource({
        "order.created, order.created, true",
        "order.created, order.updated, false",
        "order.created, order, false",
        // A trailing wildcard matches the whole remaining tail.
        "order.*, order.created, true",
        "order.*, order.updated, true",
        "order.*, order.line.added, true",
        "order.*, orders.created, false",
        "order.*, order, false",
        // '*' matches everything.
        "*, anything.at.all, true",
        // Matching is case-sensitive: event types are machine identifiers.
        "order.created, Order.Created, false",
    })
    void matchesPatterns(String pattern, String eventType, boolean expected) {
        assertThat(WebhookEndpoint.matches(pattern, eventType)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an empty subscription list means every event type")
    void emptyMeansAll() {
        // Explicit rather than implied by a null, so "subscribe to everything"
        // is a state the data model can actually represent.
        WebhookEndpoint endpoint = endpoint(Set.of());
        assertThat(endpoint.subscribesTo("order.created")).isTrue();
        assertThat(endpoint.subscribesTo("anything")).isTrue();
    }

    @Test
    @DisplayName("any matching pattern is enough")
    void anyPatternMatches() {
        WebhookEndpoint endpoint = endpoint(Set.of("invoice.paid", "order.*"));
        assertThat(endpoint.subscribesTo("order.created")).isTrue();
        assertThat(endpoint.subscribesTo("invoice.paid")).isTrue();
        assertThat(endpoint.subscribesTo("user.deleted")).isFalse();
    }

    @Test
    @DisplayName("null inputs never match and never throw")
    void handlesNulls() {
        assertThat(WebhookEndpoint.matches(null, "order.created")).isFalse();
        assertThat(WebhookEndpoint.matches("order.*", null)).isFalse();
    }

    @Test
    @DisplayName("a paused endpoint is not deliverable but is not deleted")
    void pauseStopsDelivery() {
        WebhookEndpoint endpoint = endpoint(Set.of("order.*"));
        assertThat(endpoint.isDeliverable()).isTrue();

        endpoint.pause();
        assertThat(endpoint.isDeliverable()).isFalse();
        assertThat(endpoint.getPausedAt()).isNotNull();
        assertThat(endpoint.getDeletedAt()).isNull();

        endpoint.resume();
        assertThat(endpoint.isDeliverable()).isTrue();
        assertThat(endpoint.getPausedAt()).isNull();
    }

    @Test
    @DisplayName("pausing twice keeps the original timestamp")
    void pauseIsIdempotent() {
        WebhookEndpoint endpoint = endpoint(Set.of());
        endpoint.pause();
        var first = endpoint.getPausedAt();
        endpoint.pause();
        assertThat(endpoint.getPausedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("a paused endpoint still accepts new deliveries")
    void pausedEndpointStillAcceptsDeliveries() {
        // The distinction that makes pause safe. "Not deliverable right now"
        // and "should receive no deliveries at all" are different questions:
        // if ingestion skipped paused endpoints, every event published during a
        // maintenance window would be lost with no delivery row to replay.
        WebhookEndpoint endpoint = endpoint(Set.of());
        endpoint.pause();

        assertThat(endpoint.isDeliverable()).isFalse();
        assertThat(endpoint.acceptsNewDeliveries()).isTrue();
    }

    @Test
    @DisplayName("a deleted or deactivated endpoint accepts no new deliveries")
    void deletedEndpointAcceptsNothing() {
        // Unlike a pause, there is no expectation that these come back, so
        // creating deliveries for them would only fill the dead-letter queue
        // with entries nobody can act on.
        WebhookEndpoint deleted = endpoint(Set.of());
        deleted.softDelete();
        assertThat(deleted.acceptsNewDeliveries()).isFalse();

        WebhookEndpoint deactivated = endpoint(Set.of());
        deactivated.setActive(false);
        assertThat(deactivated.acceptsNewDeliveries()).isFalse();
    }

    @Test
    @DisplayName("a deleted endpoint is inactive and not deliverable")
    void softDeleteStopsDelivery() {
        WebhookEndpoint endpoint = endpoint(Set.of());
        endpoint.softDelete();
        assertThat(endpoint.isDeliverable()).isFalse();
        assertThat(endpoint.isActive()).isFalse();
        assertThat(endpoint.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("rotation retains the previous secret and records when")
    void rotationRetainsPrevious() {
        WebhookEndpoint endpoint = endpoint(Set.of());
        assertThat(endpoint.getEncryptedSecret()).isEqualTo("sealed");
        assertThat(endpoint.getPreviousEncryptedSecret()).isNull();

        endpoint.rotateSecret("sealed-v2");

        assertThat(endpoint.getEncryptedSecret()).isEqualTo("sealed-v2");
        // Retained so signatures under both are accepted during the grace
        // window; without the overlap, rotation would require simultaneous
        // deployment on both sides and nobody would ever rotate.
        assertThat(endpoint.getPreviousEncryptedSecret()).isEqualTo("sealed");
        assertThat(endpoint.getSecretRotatedAt()).isNotNull();

        endpoint.forgetPreviousSecret();
        assertThat(endpoint.getPreviousEncryptedSecret()).isNull();
    }
}

package com.relayforge.playground;

import com.relayforge.common.RelayForgeException;
import com.relayforge.config.RelayForgeProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the webhook simulator's control plane.
 *
 * <h2>Why this does not go through the SSRF-guarded delivery client</h2>
 *
 * This is a call to a known, operator-configured internal service, not to a
 * user-supplied URL — the two have opposite trust models. The base URL comes
 * from configuration; no part of it is caller-controlled. The only
 * caller-influenced value is the target token, and it is a server-generated
 * opaque string that is additionally path-escaped here.
 *
 * <p>The delivery path to the simulator's {@code /receive} endpoint is a
 * different matter: that <em>is</em> treated as a user-supplied webhook URL and
 * goes through the full destination policy, reachable only because the
 * simulator's hostname is on the development egress allowlist. Configuring a
 * scenario and delivering to it deliberately take different paths.
 */
@Component
public class SimulatorClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatorClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public SimulatorClient(RelayForgeProperties properties, RestClient.Builder builder) {
        this.baseUrl = stripTrailingSlash(properties.playground().simulatorBaseUrl());
        this.restClient =
                builder.baseUrl(this.baseUrl)
                        .requestFactory(timeoutedRequestFactory())
                        .build();
    }

    /** The simulator's view of a target. */
    public record TargetView(
            String token, Integer requestCount, Integer failures, Integer successes) {}

    /**
     * Configures a target.
     *
     * @throws RelayForgeException when the simulator is unreachable — surfaced as
     *     503 with a clear message, because "the playground is unavailable" is an
     *     environment problem the user can act on, not a bug in their request
     */
    public void configureTarget(String token, Map<String, Object> config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", config);
        try {
            restClient
                    .put()
                    .uri("/simulator/targets/{token}", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Could not configure simulator target {}", token, e);
            throw new RelayForgeException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "simulator_unavailable",
                    "The webhook simulator could not be reached at "
                            + baseUrl
                            + ". Start it with `docker compose up webhook-simulator` and try"
                            + " again.",
                    e);
        }
    }

    /**
     * Reads the simulator's own counters for a target.
     *
     * <p>Returns {@code null} rather than throwing: this is supplementary colour
     * on the run view, and the authoritative record is RelayForge's own attempt
     * history. A simulator restart should not blank out a run the user is
     * watching.
     */
    public TargetView inspectTarget(String token) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    restClient
                            .get()
                            .uri("/simulator/targets/{token}", token)
                            .retrieve()
                            .body(Map.class);
            if (response == null) {
                return null;
            }
            return new TargetView(
                    token,
                    asInt(response.get("requestCount")),
                    asInt(response.get("failures")),
                    asInt(response.get("successes")));
        } catch (RestClientException e) {
            log.debug("Could not inspect simulator target {}", token, e);
            return null;
        }
    }

    /** The URL RelayForge will deliver to for this target. */
    public String receiveUrl(String token) {
        return baseUrl + "/simulator/receive/" + token;
    }

    public String receivePath(String token) {
        return "/simulator/receive/" + token;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Short timeouts.
     *
     * <p>The control plane is called while a user waits on a page load, and the
     * simulator is on the same Docker network — so a call that takes seconds means
     * it is down, and failing fast is better than making the dashboard hang.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory
            timeoutedRequestFactory() {
        var settings =
                org.springframework.boot.http.client.ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(2))
                        .withReadTimeout(Duration.ofSeconds(5));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }
}

package com.relayforge.playground;

import com.relayforge.playground.dto.PlaygroundDtos;
import com.relayforge.security.CurrentPrincipal;
import com.relayforge.security.RelayForgePrincipal;
import com.relayforge.tenancy.TenantRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Reliability Playground.
 *
 * <p>Deliberately injects failures into the real delivery pipeline so that
 * retries, backoff, timeouts, dead-lettering and replay can be observed working
 * rather than described. Every run uses a genuine endpoint, a genuine event and
 * the ordinary workers; the UI reads back the platform's own records.
 */
@RestController
@RequestMapping("/api/v1/playground")
@Tag(
        name = "Reliability Playground",
        description =
                "Controlled fault injection against the real delivery pipeline, for observing"
                        + " retry, recovery and dead-letter behaviour")
public class PlaygroundController {

    private final PlaygroundService service;
    private final CurrentPrincipal currentPrincipal;

    public PlaygroundController(PlaygroundService service, CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/scenarios")
    @Operation(
            summary = "List the available failure scenarios",
            description =
                    "Each scenario states what it teaches and what the expected outcome is, so a"
                            + " run can be judged correct or incorrect rather than merely watched.")
    public List<PlaygroundDtos.Scenario> scenarios() {
        // Requires authentication (enforced by the filter chain) but no
        // particular role: reading the catalogue changes nothing.
        currentPrincipal.require();
        return service.scenarios();
    }

    @PostMapping("/test-events")
    @Operation(
            summary = "Start a run",
            description =
                    "Configures the simulator, creates an endpoint pointing at it and publishes a"
                            + " real event through the normal pipeline. Returns immediately; poll"
                            + " the run to watch attempts accumulate.")
    public ResponseEntity<PlaygroundDtos.RunResponse> startRun(
            @Valid @RequestBody PlaygroundDtos.StartRunRequest request) {
        RelayForgePrincipal principal = currentPrincipal.requireRole(TenantRole.MEMBER);
        PlaygroundDtos.RunResponse response =
                service.startRun(principal.tenantId(), principal.userId(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/runs/{id}")
    @Operation(
            summary = "Read a run's current state",
            description =
                    "Returns the delivery, every recorded attempt, the countdown to the next"
                            + " attempt and the simulator's own request counters. Intended to be"
                            + " polled about once a second while a run is in progress.")
    public PlaygroundDtos.RunResponse getRun(@PathVariable UUID id) {
        return service.getRun(currentPrincipal.tenantId(), id);
    }

    @GetMapping("/runs")
    @Operation(summary = "List recent runs for this tenant")
    public List<PlaygroundDtos.RunSummary> recentRuns() {
        return service.recentRuns(currentPrincipal.tenantId());
    }
}

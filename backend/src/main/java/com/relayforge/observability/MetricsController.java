package com.relayforge.observability;

import com.relayforge.common.ValidationException;
import com.relayforge.observability.dto.MetricsDtos;
import com.relayforge.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped metrics for the dashboard. */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Per-tenant delivery statistics derived from stored data")
public class MetricsController {

    /**
     * Allowed windows.
     *
     * <p>An enumerated set rather than a free-form duration: an arbitrary window
     * would let a caller request a decade-wide aggregate and make the dashboard a
     * denial-of-service vector against its own database.
     */
    private static final Map<String, Duration> WINDOWS =
            Map.of(
                    "1h", Duration.ofHours(1),
                    "6h", Duration.ofHours(6),
                    "24h", Duration.ofHours(24),
                    "7d", Duration.ofDays(7),
                    "30d", Duration.ofDays(30));

    private final MetricsService service;
    private final CurrentPrincipal currentPrincipal;

    public MetricsController(MetricsService service, CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Overview figures for this tenant",
            description =
                    "Every value is computed from stored events, deliveries and attempts. Latency"
                            + " percentiles are approximated from histogram buckets, which the"
                            + " response flags explicitly.")
    public MetricsDtos.Summary summary(
            @Parameter(description = "One of 1h, 6h, 24h, 7d, 30d")
                    @RequestParam(defaultValue = "24h")
                    String window) {
        return service.summary(currentPrincipal.tenantId(), resolveWindow(window));
    }

    @GetMapping("/deliveries")
    @Operation(summary = "Delivery throughput, outcomes and latency over time")
    public MetricsDtos.Timeseries deliveries(@RequestParam(defaultValue = "24h") String window) {
        return service.timeseries(currentPrincipal.tenantId(), resolveWindow(window));
    }

    @GetMapping("/endpoints")
    @Operation(summary = "Per-endpoint delivery performance, least healthy first")
    public List<MetricsDtos.EndpointPerformance> endpoints() {
        return service.endpointPerformance(currentPrincipal.tenantId());
    }

    private static Duration resolveWindow(String window) {
        Duration resolved = WINDOWS.get(window == null ? "" : window.toLowerCase(java.util.Locale.ROOT));
        if (resolved == null) {
            throw new ValidationException(
                    "invalid_window",
                    "window must be one of "
                            + WINDOWS.keySet().stream().sorted().toList());
        }
        return resolved;
    }
}

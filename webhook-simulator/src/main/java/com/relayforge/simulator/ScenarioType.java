package com.relayforge.simulator;

/**
 * The failure modes the simulator can reproduce.
 *
 * <p>Each one exercises a different branch of the delivery pipeline, which is
 * what makes them worth having as distinct scenarios rather than one
 * configurable knob:
 *
 * <table>
 *   <caption>What each scenario proves</caption>
 *   <tr><th>Scenario</th><th>Exercises</th></tr>
 *   <tr><td>SUCCESS</td><td>The happy path, and the latency baseline.</td></tr>
 *   <tr><td>TEMPORARY_SERVER_ERROR</td><td>5xx classification, exponential backoff, eventual recovery.</td></tr>
 *   <tr><td>RATE_LIMITED</td><td>429 handling and {@code Retry-After} taking precedence over computed backoff.</td></tr>
 *   <tr><td>SLOW_RESPONSE</td><td>Per-attempt response timeouts, and that a slow receiver cannot pin a worker.</td></tr>
 *   <tr><td>PERMANENT_FAILURE</td><td>Attempt-budget exhaustion and dead-lettering.</td></tr>
 *   <tr><td>CONNECTION_DROP</td><td>Transport-level failure with no HTTP status at all.</td></tr>
 *   <tr><td>RECOVERY_AFTER_OUTAGE</td><td>Time-based recovery, and that retries continue across a window rather than giving up at a fixed count.</td></tr>
 * </table>
 */
public enum ScenarioType {
    SUCCESS,
    TEMPORARY_SERVER_ERROR,
    RATE_LIMITED,
    SLOW_RESPONSE,
    PERMANENT_FAILURE,
    CONNECTION_DROP,
    RECOVERY_AFTER_OUTAGE
}

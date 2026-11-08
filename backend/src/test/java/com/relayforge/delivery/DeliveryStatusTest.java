package com.relayforge.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for the delivery state machine's predicates.
 *
 * <p>These three questions — is it finished, may a worker take it, may an
 * operator replay it — are asked at every decision point in the pipeline, so
 * their definitions are pinned here rather than left to be rediscovered from
 * the call sites.
 */
class DeliveryStatusTest {

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"SUCCESS", "FAILED", "CANCELLED"})
    @DisplayName("terminal states are terminal")
    void terminalStates(DeliveryStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.isClaimable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryStatus.class,
            names = {"PENDING", "IN_PROGRESS", "RETRY_SCHEDULED"})
    @DisplayName("non-terminal states are not terminal")
    void nonTerminalStates(DeliveryStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "RETRY_SCHEDULED"})
    @DisplayName("only waiting states are claimable")
    void claimableStates(DeliveryStatus status) {
        assertThat(status.isClaimable()).isTrue();
    }

    @Test
    @DisplayName("IN_PROGRESS is not claimable")
    void inProgressIsNotClaimable() {
        // A live worker already holds it. Recovery from a dead worker goes
        // through the expired-lease branch of the claim statement, not through
        // this predicate — otherwise two workers could hold one delivery.
        assertThat(DeliveryStatus.IN_PROGRESS.isClaimable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"FAILED", "CANCELLED"})
    @DisplayName("only failed or cancelled deliveries may be replayed")
    void replayableStates(DeliveryStatus status) {
        assertThat(status.isReplayable()).isTrue();
    }

    @Test
    @DisplayName("a succeeded delivery is not replayable")
    void successIsNotReplayable() {
        // Replaying a success would send a duplicate webhook for an event the
        // receiver already handled, with no failure to justify it.
        assertThat(DeliveryStatus.SUCCESS.isReplayable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryStatus.class,
            names = {"PENDING", "IN_PROGRESS", "RETRY_SCHEDULED"})
    @DisplayName("in-flight deliveries are not replayable")
    void inFlightIsNotReplayable(DeliveryStatus status) {
        // They are already going to be attempted; replaying would double the
        // work and the attempt budget.
        assertThat(status.isReplayable()).isFalse();
    }
}

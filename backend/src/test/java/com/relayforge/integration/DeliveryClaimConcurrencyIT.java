package com.relayforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayforge.common.Ids;
import com.relayforge.delivery.Delivery;
import com.relayforge.delivery.DeliveryClaimDao;
import com.relayforge.delivery.DeliveryRepository;
import com.relayforge.delivery.DeliveryStatus;
import com.relayforge.delivery.ErrorClass;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The most important integration test in the project.
 *
 * <p>Everything about RelayForge's correctness under concurrency rests on one
 * claim: a conditional {@code UPDATE} lets exactly one worker take a delivery,
 * however many try at once. If that is wrong, duplicate Kafka messages become
 * duplicate webhooks, and the whole "at-least-once messaging, effectively-once
 * processing" story collapses.
 *
 * <p>These tests run real threads against a real PostgreSQL. A mock cannot
 * demonstrate this property, because the property <em>is</em> the database's
 * behaviour.
 */
class DeliveryClaimConcurrencyIT extends IntegrationTestBase {

    /**
     * No workers in this context.
     *
     * <p>These tests insert delivery rows and assert on their exact state, so a
     * background dispatcher claiming and attempting those rows would make the
     * assertions race. Running as {@code api} means the @WorkerRole beans are
     * never created.
     */
    @org.springframework.test.context.DynamicPropertySource
    static void roleProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("relayforge.role", () -> "api");
    }

    @Autowired private DeliveryClaimDao claimDao;
    @Autowired private DeliveryRepository deliveries;

    private UUID tenantId;
    private UUID endpointId;

    @BeforeEach
    void setUp() {
        resetDatabase();
        tenantId = Ids.newId();
        endpointId = Ids.newId();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, 'Test', ?)",
                tenantId,
                "t-" + tenantId.toString().substring(0, 8));
        jdbc.update(
                """
                INSERT INTO webhook_endpoints (id, tenant_id, name, url, encrypted_secret)
                VALUES (?, ?, 'test', 'https://example.com/hook', 'sealed')
                """,
                endpointId,
                tenantId);
    }

    private UUID insertDelivery(DeliveryStatus status, Instant nextAttemptAt, int attemptCount) {
        UUID eventId = Ids.newId();
        jdbc.update(
                """
                INSERT INTO events (id, tenant_id, event_type, payload)
                VALUES (?, ?, 'order.created', '{}'::jsonb)
                """,
                eventId,
                tenantId);
        UUID deliveryId = Ids.newId();
        jdbc.update(
                """
                INSERT INTO deliveries
                    (id, tenant_id, event_id, endpoint_id, status, attempt_count,
                     max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, 6, ?)
                """,
                deliveryId,
                tenantId,
                eventId,
                endpointId,
                status.name(),
                attemptCount,
                nextAttemptAt == null ? null : java.sql.Timestamp.from(nextAttemptAt));
        return deliveryId;
    }

    @Test
    @DisplayName("exactly one of 32 concurrent workers claims a delivery")
    void onlyOneWorkerClaims() throws Exception {
        UUID deliveryId = insertDelivery(DeliveryStatus.PENDING, null, 0);

        int workers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        // All threads released at the same instant, so they genuinely contend
        // rather than running one after another.
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        List<Callable<Void>> tasks =
                java.util.stream.IntStream.range(0, workers)
                        .mapToObj(
                                i ->
                                        (Callable<Void>)
                                                () -> {
                                                    start.await();
                                                    if (claimDao.claim(
                                                            deliveryId,
                                                            "worker-" + i,
                                                            Instant.now(),
                                                            Duration.ofSeconds(30))) {
                                                        successes.incrementAndGet();
                                                    }
                                                    return null;
                                                })
                        .toList();

        List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get())
                .as("exactly one worker must win the claim")
                .isEqualTo(1);

        Delivery claimed = deliveries.findById(deliveryId).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(DeliveryStatus.IN_PROGRESS);
        assertThat(claimed.getLeaseOwner()).startsWith("worker-");
        assertThat(claimed.getLeaseUntil()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("a duplicate message cannot claim a delivery already in progress")
    void duplicateMessageIsNoOp() {
        UUID deliveryId = insertDelivery(DeliveryStatus.PENDING, null, 0);

        assertThat(claimDao.claim(deliveryId, "worker-a", Instant.now(), Duration.ofSeconds(30)))
                .isTrue();
        // This is the duplicate-Kafka-message path: it must cost one no-op
        // UPDATE and nothing else.
        assertThat(claimDao.claim(deliveryId, "worker-b", Instant.now(), Duration.ofSeconds(30)))
                .isFalse();

        assertThat(deliveries.findById(deliveryId).orElseThrow().getLeaseOwner())
                .isEqualTo("worker-a");
    }

    @Test
    @DisplayName("a terminal delivery cannot be claimed")
    void terminalDeliveryIsNotClaimable() {
        UUID succeeded = insertDelivery(DeliveryStatus.SUCCESS, null, 1);
        UUID failed = insertDelivery(DeliveryStatus.FAILED, null, 6);
        UUID cancelled = insertDelivery(DeliveryStatus.CANCELLED, null, 2);

        Instant now = Instant.now();
        assertThat(claimDao.claim(succeeded, "w", now, Duration.ofSeconds(30))).isFalse();
        assertThat(claimDao.claim(failed, "w", now, Duration.ofSeconds(30))).isFalse();
        assertThat(claimDao.claim(cancelled, "w", now, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("a retry scheduled for the future cannot be claimed early")
    void futureRetryIsNotClaimable() {
        UUID notYetDue =
                insertDelivery(
                        DeliveryStatus.RETRY_SCHEDULED, Instant.now().plusSeconds(600), 1);
        assertThat(claimDao.claim(notYetDue, "w", Instant.now(), Duration.ofSeconds(30)))
                .isFalse();

        UUID due =
                insertDelivery(DeliveryStatus.RETRY_SCHEDULED, Instant.now().minusSeconds(1), 1);
        assertThat(claimDao.claim(due, "w", Instant.now(), Duration.ofSeconds(30))).isTrue();
    }

    @Test
    @DisplayName("an expired lease lets another worker take over")
    void expiredLeaseIsReclaimable() {
        UUID deliveryId = insertDelivery(DeliveryStatus.PENDING, null, 0);

        // Claim with a lease that has already elapsed, simulating a worker that
        // died while holding it.
        assertThat(claimDao.claim(deliveryId, "dead-worker", Instant.now(), Duration.ofMillis(1)))
                .isTrue();

        Instant later = Instant.now().plusSeconds(5);
        assertThat(claimDao.claim(deliveryId, "live-worker", later, Duration.ofSeconds(30)))
                .as("an expired lease is positive evidence the holder is gone")
                .isTrue();
        assertThat(deliveries.findById(deliveryId).orElseThrow().getLeaseOwner())
                .isEqualTo("live-worker");
    }

    @Test
    @DisplayName("a worker cannot complete a delivery whose lease it lost")
    void staleWorkerCannotOverwriteResult() {
        UUID deliveryId = insertDelivery(DeliveryStatus.PENDING, null, 0);
        claimDao.claim(deliveryId, "worker-a", Instant.now(), Duration.ofMillis(1));
        // The reconciler reclaims it and hands it to another worker.
        claimDao.claim(deliveryId, "worker-b", Instant.now().plusSeconds(5), Duration.ofSeconds(30));

        // worker-a, unblocked from a slow network call, tries to record its
        // result. Guarding every completion on lease_owner is what stops it
        // overwriting the decision already made.
        assertThat(claimDao.completeSuccess(deliveryId, "worker-a", 1, 200, Instant.now()))
                .isFalse();
        assertThat(deliveries.findById(deliveryId).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.IN_PROGRESS);

        assertThat(claimDao.completeSuccess(deliveryId, "worker-b", 1, 200, Instant.now()))
                .isTrue();
        assertThat(deliveries.findById(deliveryId).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.SUCCESS);
    }

    @Test
    @DisplayName("concurrent dispatchers claim disjoint batches")
    void dispatchersGetDisjointWork() throws Exception {
        // SKIP LOCKED is what lets several pollers share a queue without
        // coordination. If it were not honoured, they would hand the same
        // delivery to multiple workers.
        for (int i = 0; i < 60; i++) {
            insertDelivery(DeliveryStatus.PENDING, null, 0);
        }

        int dispatchers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
        CountDownLatch start = new CountDownLatch(1);

        // staleBefore must be in the past, as it is in production (now minus
        // the requeue grace). Passing `now` would disable the grace entirely:
        // a row another dispatcher had just stamped would immediately satisfy
        // `queued_at <= staleBefore` and be re-claimed the moment that
        // transaction committed. SKIP LOCKED only protects overlapping
        // transactions; the grace window is what protects committed ones.
        Instant staleBefore = Instant.now().minusSeconds(60);

        List<Future<List<DeliveryClaimDao.DispatchableDelivery>>> futures =
                java.util.stream.IntStream.range(0, dispatchers)
                        .mapToObj(
                                i ->
                                        pool.submit(
                                                () -> {
                                                    start.await();
                                                    return claimDao.claimDueForDispatch(
                                                            Instant.now(), staleBefore, 20);
                                                }))
                        .toList();
        start.countDown();

        List<UUID> allClaimed = new java.util.ArrayList<>();
        for (var future : futures) {
            allClaimed.addAll(
                    future.get(30, TimeUnit.SECONDS).stream()
                            .map(DeliveryClaimDao.DispatchableDelivery::id)
                            .toList());
        }
        pool.shutdown();

        assertThat(allClaimed).isNotEmpty();
        assertThat(allClaimed)
                .as("no delivery may be dispatched by two dispatchers")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the dispatcher does not re-enqueue work that is still in flight")
    void respectsRequeueGrace() {
        insertDelivery(DeliveryStatus.PENDING, null, 0);

        Instant now = Instant.now();
        // First poll claims it and stamps queued_at.
        assertThat(claimDao.claimDueForDispatch(now, now, 10)).hasSize(1);

        // A second poll with a grace window that excludes just-queued rows must
        // find nothing. Without this, every poll would re-enqueue everything.
        assertThat(claimDao.claimDueForDispatch(now, now.minusSeconds(60), 10)).isEmpty();

        // Once the grace window has passed, it is re-enqueued — the recovery
        // path for a Kafka message that was lost.
        assertThat(claimDao.claimDueForDispatch(now.plusSeconds(120), now.plusSeconds(60), 10))
                .hasSize(1);
    }

    @Test
    @DisplayName("expired leases are recovered and counted against the budget")
    void reconcilerRecoversExpiredLeases() {
        UUID deliveryId = insertDelivery(DeliveryStatus.PENDING, null, 2);
        claimDao.claim(deliveryId, "dead-worker", Instant.now(), Duration.ofMillis(1));

        int recovered =
                claimDao.reclaimExpiredLeases(
                        Instant.now().plusSeconds(5), Duration.ofSeconds(1), 100);
        assertThat(recovered).isEqualTo(1);

        Delivery recoveredDelivery = deliveries.findById(deliveryId).orElseThrow();
        assertThat(recoveredDelivery.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(recoveredDelivery.getLeaseOwner()).isNull();
        // The outcome is genuinely unknown — the request may have reached the
        // receiver — and the record says so rather than claiming a failure.
        assertThat(recoveredDelivery.getLastError()).contains("may or may not have reached");
        assertThat(recoveredDelivery.getLastErrorClass()).isEqualTo(ErrorClass.UNKNOWN);
    }

    @Test
    @DisplayName("a stranded delivery with no budget left is dead-lettered, not looped")
    void exhaustedStrandedDeliveryFails() {
        // attempt_count == max_attempts: recovery must not grant a free retry,
        // or a crash-looping worker would escape the attempt budget entirely.
        UUID deliveryId = insertDelivery(DeliveryStatus.PENDING, null, 6);
        claimDao.claim(deliveryId, "dead-worker", Instant.now(), Duration.ofMillis(1));

        claimDao.reclaimExpiredLeases(Instant.now().plusSeconds(5), Duration.ofSeconds(1), 100);

        assertThat(deliveries.findById(deliveryId).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("concurrent replays of one delivery produce exactly one replay")
    void concurrentReplayIsSingular() throws Exception {
        UUID deliveryId = insertDelivery(DeliveryStatus.FAILED, null, 6);

        int actors = 16;
        ExecutorService pool = Executors.newFixedThreadPool(actors);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        List<Future<Void>> futures =
                java.util.stream.IntStream.range(0, actors)
                        .mapToObj(
                                i ->
                                        pool.submit(
                                                (Callable<Void>)
                                                        () -> {
                                                            start.await();
                                                            if (claimDao.reopenForReplay(
                                                                    deliveryId,
                                                                    tenantId,
                                                                    3,
                                                                    Instant.now())) {
                                                                accepted.incrementAndGet();
                                                            }
                                                            return null;
                                                        }))
                        .toList();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get())
                .as("two operators clicking replay must produce one replay")
                .isEqualTo(1);

        Delivery replayed = deliveries.findById(deliveryId).orElseThrow();
        assertThat(replayed.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(replayed.getReplayCount()).isEqualTo(1);
        // The budget is extended, not reset: attempt numbering keeps marching
        // forward so the history stays coherent.
        assertThat(replayed.getAttemptCount()).isEqualTo(6);
        assertThat(replayed.getMaxAttempts()).isEqualTo(9);
    }

    @Test
    @DisplayName("replay is tenant-scoped")
    void replayIsTenantScoped() {
        UUID deliveryId = insertDelivery(DeliveryStatus.FAILED, null, 6);
        assertThat(claimDao.reopenForReplay(deliveryId, Ids.newId(), 3, Instant.now()))
                .as("another tenant's id must not reopen this delivery")
                .isFalse();
    }
}

package com.relayforge.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Entity-level access to deliveries.
 *
 * <p>The atomic claim SQL lives in {@link DeliveryClaimDao}; the optional-filter
 * search predicates live in {@link DeliverySpecifications}.
 */
public interface DeliveryRepository
        extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {

    Optional<Delivery> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Delivery> findByEventIdOrderByCreatedAtAsc(UUID eventId);

    List<Delivery> findByEventIdAndTenantIdOrderByCreatedAtAsc(UUID eventId, UUID tenantId);

    @Query(
            """
            SELECT d FROM Delivery d
             WHERE d.tenantId = :tenantId
               AND d.status = com.relayforge.delivery.DeliveryStatus.FAILED
               AND d.endpointId = :endpointId
            """)
    List<Delivery> findAllDeadLettersForEndpoint(
            @Param("tenantId") UUID tenantId, @Param("endpointId") UUID endpointId);

    long countByTenantIdAndStatus(UUID tenantId, DeliveryStatus status);

    long countByTenantId(UUID tenantId);

    @Query(
            """
            SELECT COUNT(d) FROM Delivery d
             WHERE d.tenantId = :tenantId
               AND d.status IN (com.relayforge.delivery.DeliveryStatus.PENDING,
                                com.relayforge.delivery.DeliveryStatus.IN_PROGRESS,
                                com.relayforge.delivery.DeliveryStatus.RETRY_SCHEDULED)
            """)
    long countInFlight(@Param("tenantId") UUID tenantId);

    @Query(
            """
            SELECT d.status, COUNT(d) FROM Delivery d
             WHERE d.tenantId = :tenantId
               AND d.createdAt >= :since
             GROUP BY d.status
            """)
    List<Object[]> countByStatusSince(
            @Param("tenantId") UUID tenantId, @Param("since") Instant since);

    /** Per-event status counts for a page of events, in one grouped query. */
    @Query(
            """
            SELECT d.eventId, d.status, COUNT(d) FROM Delivery d
             WHERE d.eventId IN :eventIds
             GROUP BY d.eventId, d.status
            """)
    List<Object[]> countByEventAndStatus(@Param("eventIds") java.util.Collection<UUID> eventIds);

    @Query(
            """
            SELECT d.endpointId, d.status, COUNT(d) FROM Delivery d
             WHERE d.tenantId = :tenantId
               AND d.createdAt >= :since
             GROUP BY d.endpointId, d.status
            """)
    List<Object[]> countByEndpointAndStatusSince(
            @Param("tenantId") UUID tenantId, @Param("since") Instant since);

    /**
     * Cancels every non-terminal delivery for an endpoint.
     *
     * <p>Used when an endpoint is deleted: leaving work queued for a destination
     * that no longer exists would burn the whole retry budget producing DNS
     * failures, and would keep the dead-letter queue full of deliveries nobody
     * can act on.
     */
    @Modifying
    @Query(
            """
            UPDATE Delivery d
               SET d.status = com.relayforge.delivery.DeliveryStatus.CANCELLED,
                   d.nextAttemptAt = NULL,
                   d.completedAt = :now,
                   d.lastError = :reason,
                   d.updatedAt = :now
             WHERE d.endpointId = :endpointId
               AND d.tenantId = :tenantId
               AND d.status IN (com.relayforge.delivery.DeliveryStatus.PENDING,
                                com.relayforge.delivery.DeliveryStatus.RETRY_SCHEDULED)
            """)
    int cancelOpenDeliveriesForEndpoint(
            @Param("tenantId") UUID tenantId,
            @Param("endpointId") UUID endpointId,
            @Param("now") Instant now,
            @Param("reason") String reason);
}

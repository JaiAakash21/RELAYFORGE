package com.relayforge.events;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Entity-level access to events.
 *
 * <p>Optional-filter search predicates live in {@link EventSpecifications}.
 */
public interface EventRepository
        extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

    Optional<Event> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Event> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant after);

    @Query(
            """
            SELECT DISTINCT e.eventType FROM Event e
             WHERE e.tenantId = :tenantId
             ORDER BY e.eventType
            """)
    java.util.List<String> findDistinctEventTypes(@Param("tenantId") UUID tenantId);
}

package com.relayforge.events;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Event> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Event history with optional filters.
     *
     * <p>All filters are nullable and collapse to {@code TRUE} when absent, so
     * one query and one index serve the whole filter matrix rather than needing a
     * Specification tree. The leading predicate is always {@code tenantId}, which
     * matches {@code events_tenant_created_idx}.
     */
    @Query(
            """
            SELECT e FROM Event e
             WHERE e.tenantId = :tenantId
               AND (:eventType IS NULL OR e.eventType = :eventType)
               AND (:search   IS NULL OR LOWER(e.eventType) LIKE LOWER(CONCAT('%', :search, '%')))
               AND (:from     IS NULL OR e.createdAt >= :from)
               AND (:to       IS NULL OR e.createdAt <= :to)
             ORDER BY e.createdAt DESC, e.id DESC
            """)
    Page<Event> search(
            @Param("tenantId") UUID tenantId,
            @Param("eventType") String eventType,
            @Param("search") String search,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

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

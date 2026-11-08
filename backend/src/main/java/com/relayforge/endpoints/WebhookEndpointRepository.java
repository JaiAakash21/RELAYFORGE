package com.relayforge.endpoints;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    /**
     * Tenant-scoped lookup. Every read path uses this rather than
     * {@code findById}, so a cross-tenant identifier simply does not resolve —
     * isolation is a property of the query, not of a later {@code if}.
     */
    @Query(
            """
            SELECT e FROM WebhookEndpoint e
             WHERE e.id = :id
               AND e.tenantId = :tenantId
               AND e.deletedAt IS NULL
            """)
    Optional<WebhookEndpoint> findActiveByIdAndTenant(
            @Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query(
            """
            SELECT e FROM WebhookEndpoint e
             WHERE e.tenantId = :tenantId
               AND e.deletedAt IS NULL
             ORDER BY e.createdAt DESC
            """)
    Page<WebhookEndpoint> findByTenant(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query(
            """
            SELECT e FROM WebhookEndpoint e
             WHERE e.tenantId = :tenantId
               AND e.deletedAt IS NULL
             ORDER BY e.createdAt DESC
            """)
    List<WebhookEndpoint> findAllByTenant(@Param("tenantId") UUID tenantId);

    /**
     * Candidate endpoints for an ingested event.
     *
     * <p>Deliberately selects every deliverable endpoint of the tenant and lets
     * {@link WebhookEndpoint#subscribesTo} make the final decision in memory.
     *
     * <p>Why not push the wildcard match into SQL: a tenant has tens of
     * endpoints, not millions, so the scan is bounded and cheap, while a
     * SQL-side pattern match would have to duplicate the wildcard semantics in a
     * second place. One definition of the matching rule is worth more here than
     * an index lookup we do not need. The GIN index on the array is still used
     * by the admin "who subscribes to X?" query.
     *
     * <p><b>Paused endpoints are included.</b> Pause means "hold my events",
     * not "drop them": the delivery is created and the worker holds it until
     * the endpoint resumes. Excluding paused endpoints here would silently lose
     * every event published during a maintenance window, leaving no delivery
     * row behind to replay.
     */
    @Query(
            """
            SELECT e FROM WebhookEndpoint e
             WHERE e.tenantId = :tenantId
               AND e.deletedAt IS NULL
               AND e.active = true
            """)
    List<WebhookEndpoint> findSubscribableByTenant(@Param("tenantId") UUID tenantId);

    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
}

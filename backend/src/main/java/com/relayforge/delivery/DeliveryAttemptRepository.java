package com.relayforge.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    List<DeliveryAttempt> findByDeliveryIdAndTenantIdOrderByAttemptNumberAsc(
            UUID deliveryId, UUID tenantId);

    long countByTenantIdAndStartedAtAfter(UUID tenantId, Instant after);

    @Query(
            """
            SELECT COUNT(a) FROM DeliveryAttempt a
             WHERE a.tenantId = :tenantId
               AND a.attemptNumber > 1
               AND a.startedAt >= :since
            """)
    long countRetriesSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}

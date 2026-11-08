package com.relayforge.deadletter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayRecordRepository extends JpaRepository<ReplayRecord, UUID> {

    List<ReplayRecord> findByDeliveryIdOrderByCreatedAtDesc(UUID deliveryId);

    List<ReplayRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant after);
}

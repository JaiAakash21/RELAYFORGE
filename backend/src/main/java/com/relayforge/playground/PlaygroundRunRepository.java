package com.relayforge.playground;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaygroundRunRepository extends JpaRepository<PlaygroundRun, UUID> {

    Optional<PlaygroundRun> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<PlaygroundRun> findByDeliveryId(UUID deliveryId);

    List<PlaygroundRun> findTop20ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}

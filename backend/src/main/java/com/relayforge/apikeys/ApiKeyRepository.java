package com.relayforge.apikeys;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByPrefix(String prefix);

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Records usage without loading the entity or taking a write lock on the hot
     * authentication path. Executed outside the request's own transaction so a
     * failure here can never fail an otherwise-valid ingestion.
     */
    @Modifying
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now);
}

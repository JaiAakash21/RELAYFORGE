package com.relayforge.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, UUID aggregateId);

    @Query(
            """
            SELECT COUNT(o) FROM OutboxEvent o
             WHERE o.status IN (com.relayforge.outbox.OutboxStatus.PENDING,
                                com.relayforge.outbox.OutboxStatus.CLAIMED)
            """)
    long countUnpublished();

    /** Age of the oldest unpublished row — the outbox's own lag signal. */
    @Query(
            """
            SELECT MIN(o.createdAt) FROM OutboxEvent o
             WHERE o.status IN (com.relayforge.outbox.OutboxStatus.PENDING,
                                com.relayforge.outbox.OutboxStatus.CLAIMED)
            """)
    Instant findOldestUnpublishedCreatedAt();

    @Query("DELETE FROM OutboxEvent o WHERE o.status = com.relayforge.outbox.OutboxStatus.PUBLISHED AND o.publishedAt < :cutoff")
    @org.springframework.data.jpa.repository.Modifying
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}

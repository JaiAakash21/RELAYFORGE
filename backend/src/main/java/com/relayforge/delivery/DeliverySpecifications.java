package com.relayforge.delivery;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable predicates for delivery queries.
 *
 * <h2>Why these are built dynamically rather than written as one JPQL query</h2>
 *
 * The tempting shortcut for optional filters is a single query whose unused
 * predicates collapse to true:
 *
 * <pre>{@code  WHERE (:status IS NULL OR d.status = :status) }</pre>
 *
 * It is wrong here for two independent reasons.
 *
 * <p><b>It does not work.</b> PostgreSQL infers a bind parameter's type from
 * context, and {@code ? IS NULL} supplies none. The planner rejects the
 * statement outright with "could not determine data type of parameter" — which is
 * exactly the failure this class was written to fix.
 *
 * <p><b>Even where it works, it is slower.</b> The predicate stays in the plan
 * whether or not the filter was supplied, so the planner has to produce one plan
 * that is correct for every combination of null and non-null parameters. It
 * cannot use the selectivity of a filter it might not be given, which tends to
 * cost exactly the index choice the filter existed to enable.
 *
 * <p>Building only the predicates that were actually supplied gives PostgreSQL a
 * statement describing precisely the question being asked. The tenant predicate
 * is always present and always first, so {@code deliveries_tenant_created_idx}
 * remains usable.
 */
public final class DeliverySpecifications {

    private DeliverySpecifications() {}

    /**
     * Builds the delivery search predicate.
     *
     * <p>{@code tenantId} is a required parameter rather than an optional filter.
     * That is deliberate: a tenant-scoped query with an accidentally absent
     * tenant predicate returns every tenant's data, so the type system is used to
     * make forgetting it impossible.
     */
    public static Specification<Delivery> search(
            UUID tenantId,
            DeliveryStatus status,
            UUID endpointId,
            UUID eventId,
            Instant from,
            Instant to) {

        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(6);
            predicates.add(builder.equal(root.get("tenantId"), tenantId));

            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (endpointId != null) {
                predicates.add(builder.equal(root.get("endpointId"), endpointId));
            }
            if (eventId != null) {
                predicates.add(builder.equal(root.get("eventId"), eventId));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** The dead-letter queue: exhausted deliveries, optionally for one endpoint. */
    public static Specification<Delivery> deadLetters(UUID tenantId, UUID endpointId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(3);
            predicates.add(builder.equal(root.get("tenantId"), tenantId));
            predicates.add(builder.equal(root.get("status"), DeliveryStatus.FAILED));
            if (endpointId != null) {
                predicates.add(builder.equal(root.get("endpointId"), endpointId));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}

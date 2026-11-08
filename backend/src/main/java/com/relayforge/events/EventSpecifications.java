package com.relayforge.events;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable predicates for event history queries.
 *
 * <p>See {@link com.relayforge.delivery.DeliverySpecifications} for why optional
 * filters are built dynamically rather than written as {@code (:p IS NULL OR …)}.
 */
public final class EventSpecifications {

    private EventSpecifications() {}

    public static Specification<Event> search(
            UUID tenantId, String eventType, String search, Instant from, Instant to) {

        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            predicates.add(builder.equal(root.get("tenantId"), tenantId));

            if (eventType != null && !eventType.isBlank()) {
                predicates.add(builder.equal(root.get("eventType"), eventType));
            }
            if (search != null && !search.isBlank()) {
                // Escaped so a user typing '%' searches for a literal percent
                // sign rather than matching everything.
                String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(
                        builder.like(
                                builder.lower(root.get("eventType")), pattern, '\\'));
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

    /** Escapes the LIKE metacharacters so user input is matched literally. */
    static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

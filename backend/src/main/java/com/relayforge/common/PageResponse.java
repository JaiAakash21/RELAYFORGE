package com.relayforge.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Transport shape for a page of results.
 *
 * <p>Spring Data's own {@code Page} serialises its entire internal structure,
 * which is unstable across versions and leaks paging implementation details into
 * the public contract. This record is the contract instead.
 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages, page + 1 < totalPages);
    }
}

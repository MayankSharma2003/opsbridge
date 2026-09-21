package io.opsbridge.support.pagination;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

// Single shape every list endpoint returns -- Spring's Page serializes with an unstable,
// impl-detail-heavy structure, so responses go through this instead.
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}

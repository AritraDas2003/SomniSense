package com.somnisense.backend.common.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The ONE JSON shape for every paginated list:
 * {"content":[...], "page":0, "size":20, "totalElements":57, "totalPages":3}
 *
 * Why not return Spring's Page directly? Its JSON layout is an internal detail that can change between
 * Spring versions; this class is our stable contract with the frontend.
 * "page" is zero-based (first page = 0).
 *
 * @param <T> the type of each item (a DTO, never an entity)
 */
@JsonPropertyOrder({"content", "page", "size", "totalElements", "totalPages"})
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public PageResponse(List<T> content, int page, int size, long totalElements, int totalPages) {
        this.content = List.copyOf(content);
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    /** Converts a Spring Data page of entities into a page of DTOs using the given mapping function. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public List<T> getContent() {
        return content;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }
}

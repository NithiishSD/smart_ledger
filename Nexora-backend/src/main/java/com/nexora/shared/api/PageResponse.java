package com.nexora.shared.api;

import java.util.List;

import org.springframework.data.domain.Page;

// A "record" is an immutable data holder. Java generates for us: the constructor, the
// accessors (content(), page(), ...), equals, hashCode and toString.
// <T> is a generic type parameter: PageResponse<PartyResponse>, PageResponse<PurchaseResponse> ...
// One class serves every list endpoint.
// Why our own class and not Spring's Page<T>: the JSON shape becomes OUR stable API contract,
// not an internal Spring detail that could change in an upgrade.
public record PageResponse<T>(
        List<T> content,      // the items on this page
        int page,             // page number, starting at 0
        int size,             // requested page size
        long totalElements,   // total rows across all pages (long: tables can be large)
        int totalPages) {     // how many pages exist in total

    // "static <T>": a static method with its own type parameter, so it can be called
    // without an existing object: PageResponse.from(repository.findAll(pageable)).
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),         // the rows of the current page
                page.getNumber(),          // current page number (0-based)
                page.getSize(),            // page size
                page.getTotalElements(),   // total number of rows
                page.getTotalPages());     // total number of pages
    }
}

package com.aienterprise.backend.tracker.domain;

import java.util.List;

/** Bounded, stably sorted page of formal human-review cases. */
public record ReviewPage(
        List<ReviewCase> items,
        int page,
        int size,
        long total,
        long totalPages,
        String sort) {

    public static final String STABLE_SORT = "priority DESC, created_at ASC, id ASC";

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    public enum Reason {
        HIGH_IMPACT,
        LEVEL_JUMP,
        FLUKE_MISMATCH,
        ARRIVAL_CANDIDATE,
        CIRCUIT_BREAKER
    }

    public ReviewPage {
        items = List.copyOf(items);
    }
}

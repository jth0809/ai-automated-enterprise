package com.aienterprise.backend.tracker.collection;

import java.net.URI;
import java.time.Instant;

public record OfficialIndexEntry(URI url, String title, Instant publishedAt) {

    public OfficialIndexEntry {
        if (url == null || title == null || title.length() < 3 || title.length() > 1_000) {
            throw new IllegalArgumentException("Official index entry requires a bounded URL and title");
        }
    }
}

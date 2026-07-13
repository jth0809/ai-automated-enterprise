package com.aienterprise.backend.tracker.ingest;

import java.net.URI;

/**
 * Bounded result of an allowlisted article fetch: the final URI after
 * redirects, the lowercase media type, an optional charset hint from the
 * Content-Type header, and at most 2 MiB of raw bytes.
 */
public record FetchedPage(URI finalUri, String mediaType, String charsetHint, byte[] bytes) {
}

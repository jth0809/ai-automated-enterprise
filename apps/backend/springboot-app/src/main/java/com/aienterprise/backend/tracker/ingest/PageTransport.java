package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Raw HTTP GET abstraction under {@link ArticlePageFetcher}. Implementations
 * must NOT follow redirects — the fetcher revalidates every hop against the
 * host allowlist itself.
 */
public interface PageTransport {

    Response get(URI uri) throws IOException, InterruptedException;

    interface Response extends AutoCloseable {

        int status();

        /** Response headers; implementations use lowercase header names. */
        Map<String, List<String>> headers();

        InputStream body();

        @Override
        void close();
    }
}

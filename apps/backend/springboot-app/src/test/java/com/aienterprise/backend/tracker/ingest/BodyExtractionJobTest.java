package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class BodyExtractionJobTest {

    private static final String RSS_BODY = "RSS summary body";
    private static final String FULL_TEXT =
            "Full article text.\n\nThe transfer moved 1,200 kilograms of propellant.";

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private ArticlePageFetcher fetcher;
    private ArticleBodyExtractor extractor;
    private BodyExtractionJob job;

    @BeforeEach
    void setUp() {
        fetcher = mock(ArticlePageFetcher.class);
        extractor = mock(ArticleBodyExtractor.class);
        job = new BodyExtractionJob(fetcher, extractor, repository);
    }

    @Test
    void successfulExtractionMakesArticleVisibleToGate() {
        long articleId = insertPendingArticle("https://www.nasa.gov/story-a");
        when(fetcher.fetch(any(), anySet())).thenReturn(page("https://www.nasa.gov/story-a"));
        when(extractor.extract(any())).thenReturn(new ExtractedArticle("Title", FULL_TEXT));

        job.processPending();

        assertEquals("EXTRACTED", extractionStatus(articleId));
        assertEquals(FULL_TEXT, body(articleId));
        assertEquals("Y", jdbc.sql("SELECT body_extracted FROM article WHERE id = :id")
                .param("id", articleId).query(String.class).single());
        assertEquals(List.of(articleId),
                repository.findByStatus("INGESTED", 10).stream().map(ArticleRow::id).toList());
    }

    @Test
    void pendingArticleIsInvisibleToRelevanceGate() {
        insertPendingArticle("https://www.nasa.gov/story-b");

        assertTrue(repository.findByStatus("INGESTED", 10).isEmpty());
    }

    @Test
    void policyViolationSkipsTerminallyAndKeepsTheRssBody() {
        long articleId = insertPendingArticle("https://www.nasa.gov/story-c");
        when(fetcher.fetch(any(), anySet()))
                .thenThrow(new SecurityException("Host is not in the body-domain allowlist"));

        job.processPending();
        job.processPending();

        assertEquals("SKIPPED", extractionStatus(articleId));
        assertEquals(RSS_BODY, body(articleId));
        assertNotNull(extractionError(articleId));
        assertEquals(1, repository.findByStatus("INGESTED", 10).size());
    }

    @Test
    void transientFailuresRetryTwiceThenBecomeTerminalFailed() {
        long articleId = insertPendingArticle("https://www.nasa.gov/story-d");
        when(fetcher.fetch(any(), anySet())).thenThrow(new IllegalStateException("HTTP 503"));

        job.processPending();
        assertEquals("PENDING", extractionStatus(articleId));
        assertEquals(1, attempts(articleId));

        job.processPending();
        assertEquals("PENDING", extractionStatus(articleId));
        assertEquals(2, attempts(articleId));

        job.processPending();
        assertEquals("FAILED", extractionStatus(articleId));
        assertEquals(3, attempts(articleId));
        assertEquals(RSS_BODY, body(articleId));
        assertEquals(1, repository.findByStatus("INGESTED", 10).size());
    }

    @Test
    void oneArticleFailureDoesNotBlockAnother() {
        long broken = insertPendingArticle("https://www.nasa.gov/story-broken");
        long healthy = insertPendingArticle("https://www.nasa.gov/story-healthy");
        when(fetcher.fetch(eq(URI.create("https://www.nasa.gov/story-broken")), anySet()))
                .thenThrow(new IllegalStateException("HTTP 503"));
        when(fetcher.fetch(eq(URI.create("https://www.nasa.gov/story-healthy")), anySet()))
                .thenReturn(page("https://www.nasa.gov/story-healthy"));
        when(extractor.extract(any())).thenReturn(new ExtractedArticle("Title", FULL_TEXT));

        job.processPending();

        assertEquals("PENDING", extractionStatus(broken));
        assertEquals("EXTRACTED", extractionStatus(healthy));
    }

    private long insertPendingArticle(String url) {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class).single();
        return repository.insertArticleIfNew(
                url, sha256Like(url), sourceId, "A title",
                Instant.parse("2026-07-13T00:00:00Z"), RSS_BODY, "PENDING").orElseThrow();
    }

    private FetchedPage page(String url) {
        return new FetchedPage(URI.create(url), "text/html", "utf-8",
                "<html><body><article><p>x</p></article></body></html>".getBytes(StandardCharsets.UTF_8));
    }

    private String extractionStatus(long id) {
        return jdbc.sql("SELECT body_extraction_status FROM article WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private String extractionError(long id) {
        return jdbc.sql("SELECT body_extraction_error FROM article WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private int attempts(long id) {
        return jdbc.sql("SELECT body_extraction_attempts FROM article WHERE id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private String body(long id) {
        return jdbc.sql("SELECT body FROM article WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private static String sha256Like(String url) {
        return String.format("%064x", url.hashCode() & 0xffffffffL);
    }
}

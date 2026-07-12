package com.aienterprise.backend.tracker.ingest;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.ExtractionCandidate;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Full-text extraction lifecycle: claims the oldest PENDING articles,
 * fetches each from its allowlisted hosts, and either stores the extracted
 * text (EXTRACTED), records a bounded retry (PENDING then FAILED after three
 * attempts), or terminally skips policy/deterministic failures (SKIPPED)
 * while keeping the RSS summary as the fallback body.
 */
@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class BodyExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(BodyExtractionJob.class);
    private static final int BATCH_LIMIT = 30;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_CHARS = 500;

    private final ArticlePageFetcher fetcher;
    private final ArticleBodyExtractor extractor;
    private final TrackerRepository repository;

    public BodyExtractionJob(
            ArticlePageFetcher fetcher,
            ArticleBodyExtractor extractor,
            TrackerRepository repository) {
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.repository = repository;
    }

    @Scheduled(cron = "${tracker.extract-cron:0 15 * * * *}")
    @SchedulerLock(name = "tracker-body-extraction", lockAtLeastFor = "PT1M")
    public void runOnce() {
        processPending();
    }

    public void processPending() {
        for (ExtractionCandidate candidate : repository.findPendingExtractions(BATCH_LIMIT)) {
            try {
                FetchedPage page = fetcher.fetch(URI.create(candidate.url()), candidate.allowedHosts());
                repository.completeExtraction(candidate.id(), extractor.extract(page).text());
            } catch (SecurityException | IllegalArgumentException deterministic) {
                // Policy violations and unsuitable pages never improve on
                // retry: keep the RSS summary and release the row to the gate.
                log.warn("tracker body extraction skipped for article {}: {}",
                        candidate.id(), deterministic.toString());
                repository.skipExtraction(candidate.id(), safeMessage(deterministic));
            } catch (RuntimeException transientError) {
                String status = repository.recordExtractionFailure(
                        candidate.id(), safeMessage(transientError), MAX_ATTEMPTS);
                log.warn("tracker body extraction attempt failed for article {} ({}): {}",
                        candidate.id(), status, transientError.toString());
            }
        }
    }

    /** Class name plus bounded message — never response bodies or secrets. */
    private static String safeMessage(RuntimeException error) {
        String message = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message;
    }
}

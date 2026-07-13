package com.aienterprise.backend.tracker.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.ReviewRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.evaluate.CostGuard;
import com.aienterprise.backend.tracker.evaluate.CostLimitExceededException;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Runs the second-context fluke filter over pending reviews. Cost-cap
 * exhaustion defers work without counting a failure; ordinary failures retry
 * up to three times before the review turns FAILED at attention priority.
 * No verdict ever advances or rejects an event — humans decide.
 * Dark by default: requires both tracker.enabled and tracker.fluke-enabled.
 */
@Component
@ConditionalOnProperty(prefix = "tracker", name = {"enabled", "fluke-enabled"}, havingValue = "true")
public class FlukeFilterJob {

    private static final Logger log = LoggerFactory.getLogger(FlukeFilterJob.class);
    private static final int BATCH_LIMIT = 20;
    private static final int MAX_FAILURES = 3;
    private static final int MAX_ERROR_CHARS = 500;

    private final FlukeFilter filter;
    private final CostGuard costGuard;
    private final TrackerRepository repository;

    public FlukeFilterJob(FlukeFilter filter, CostGuard costGuard, TrackerRepository repository) {
        this.filter = filter;
        this.costGuard = costGuard;
        this.repository = repository;
    }

    @Scheduled(cron = "${tracker.fluke-cron:0 57 * * * *}")
    @SchedulerLock(name = "tracker-fluke-filter", lockAtLeastFor = "PT1M")
    public void runOnce() {
        processPending();
    }

    public void processPending() {
        for (ReviewRow review : repository.findReviewsForFluke(BATCH_LIMIT)) {
            if (!costGuard.allow()) {
                // Daily cap exhausted: leave every counter untouched and let
                // the next tick retry.
                return;
            }
            try {
                FlukeResult result = filter.evaluate(candidate(review));
                int priority = "MISMATCH".equals(result.verdict()) ? 1 : 0;
                EventRow event = repository.findEventById(review.eventId());
                repository.storeFlukeEvaluation(
                        review.id(), review.eventId(), result.verdict(), result.evidenceQuote(),
                        true, result.rawOutput(), result.modelId(), result.promptSha256(),
                        event.rubricVersionId(), priority);
            } catch (CostLimitExceededException limitReachedBetweenChecks) {
                return;
            } catch (RuntimeException failure) {
                String status = repository.recordFlukeFailure(
                        review.id(), safeMessage(failure), MAX_FAILURES);
                log.warn("tracker fluke evaluation failed for review {} ({}): {}",
                        review.id(), status, failure.toString());
            }
        }
    }

    private FlukeCandidate candidate(ReviewRow review) {
        EventRow event = repository.findEventById(review.eventId());
        NodeRow node = repository.findNodeById(event.nodeId());
        return new FlukeCandidate(
                review.id(), event.id(), node.code(), node.nameKo(), node.scaleType(),
                node.currentLevel(), event.eventType(), event.claimedLevel(), event.actor(),
                event.occurredOn(), repository.findVerifiedEvidenceBodies(event.id()));
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message;
    }
}

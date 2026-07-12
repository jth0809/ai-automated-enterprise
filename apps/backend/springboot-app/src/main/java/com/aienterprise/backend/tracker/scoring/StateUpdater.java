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
import com.aienterprise.backend.tracker.event.VerificationLevel;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class StateUpdater {

    private static final Logger log = LoggerFactory.getLogger(StateUpdater.class);
    private static final int BATCH_LIMIT = 200;
    private static final double HIGH_IMPACT_THRESHOLD = 8.0;

    private final TrackerRepository repository;

    public StateUpdater(TrackerRepository repository) {
        this.repository = repository;
    }

    public void processEvent(EventRow event) {
        NodeRow node = repository.findNodeById(event.nodeId());
        ScoreResult score = ImpactScorer.score(
                event.eventType(),
                event.claimedLevel(),
                node.currentLevel(),
                "DORMANT".equals(node.nodeStatus()),
                VerificationLevel.valueOf(event.verificationLevel()));
        repository.recordEventScore(event.id(), score.impactScore(), score.novelty());
        if (!score.stateEligible()) {
            return;
        }
        if (score.requiresReview()) {
            String reason = score.impactScore() >= HIGH_IMPACT_THRESHOLD ? "HIGH_IMPACT" : "LEVEL_JUMP";
            repository.insertReview(event.id(), reason);
            return;
        }
        advance(event);
    }

    public void approve(ReviewRow review, String note) {
        if (!repository.resolveReview(review.id(), "APPROVED", note)) {
            return;
        }
        advance(repository.findEventById(review.eventId()));
    }

    public void reject(ReviewRow review, String note) {
        if (!repository.resolveReview(review.id(), "REJECTED", note)) {
            return;
        }
        repository.markEventRejected(review.eventId());
    }

    @Scheduled(cron = "${tracker.score-cron:0 55 * * * *}")
    @SchedulerLock(name = "tracker-state-updater", lockAtLeastFor = "PT1M")
    public void runOnce() {
        processPending();
    }

    public void processPending() {
        for (EventRow event : repository.findEventsForScoring(BATCH_LIMIT)) {
            try {
                processEvent(event);
            } catch (RuntimeException e) {
                log.warn("tracker state update failed for event {}: {}", event.id(), e.toString());
            }
        }
    }

    private void advance(EventRow event) {
        repository.advanceNode(
                event.nodeId(), event.claimedLevel(), event.verificationLevel(),
                event.id(), event.rubricVersionId());
        repository.markEventConfirmed(event.id());
    }
}

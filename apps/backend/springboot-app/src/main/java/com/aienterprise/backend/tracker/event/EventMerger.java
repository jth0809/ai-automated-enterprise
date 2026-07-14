package com.aienterprise.backend.tracker.event;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.ClassificationRow;
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.MergeCandidate;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class EventMerger {

    private static final Logger log = LoggerFactory.getLogger(EventMerger.class);
    private static final int PROVISIONAL_TTL_DAYS = 90;
    private static final int BATCH_LIMIT = 200;
    /** Below this token count a quote is too thin to trust for semantic merge. */
    private static final int MIN_SEMANTIC_TOKENS = 4;

    private final TrackerRepository repository;
    private final SemanticCandidateMatcher matcher;

    public EventMerger(TrackerRepository repository) {
        this.repository = repository;
        this.matcher = new SemanticCandidateMatcher();
    }

    public long mergeClaim(ClassificationRow claim) {
        NodeRow node = repository.findNodeByCode(claim.nodeCode());
        String naturalKey = naturalKey(claim);
        long eventId = resolveEvent(claim, node, naturalKey);
        repository.linkClassification(claim.id(), eventId);
        VerificationLevel derived = VerificationDeriver.derive(repository.findClusterEvidence(eventId));
        repository.updateEventVerification(eventId, derived.name());
        return eventId;
    }

    /**
     * Exact natural key first (always wins), then a safe semantic match, then a
     * new natural-key event. Ambiguity or a thin quote falls through to a new
     * event rather than guessing a merge.
     */
    private long resolveEvent(ClassificationRow claim, NodeRow node, String naturalKey) {
        Optional<Long> exact = repository.findEventIdByNaturalKey(naturalKey);
        if (exact.isPresent()) {
            return exact.get();
        }
        Optional<Long> semantic = matchSemantically(claim, node, naturalKey);
        if (semantic.isPresent()) {
            return semantic.get();
        }
        EventRow draft = EventRow.draft(
                node.id(), claim.eventType(), claim.claimedLevel(), claim.actor(),
                claim.occurredOn(), VerificationLevel.CLAIMED.name(),
                LocalDate.now(ZoneOffset.UTC).plusDays(PROVISIONAL_TTL_DAYS),
                claim.rubricVersionId());
        return repository.upsertEventByNaturalKey(naturalKey, draft);
    }

    private Optional<Long> matchSemantically(
            ClassificationRow claim, NodeRow node, String naturalKey) {
        String quote = claim.evidenceQuote();
        if (!hasEnoughTokens(quote)) {
            return Optional.empty();
        }
        List<MergeCandidate> candidates = repository.findMergeCandidates(
                node.id(), claim.eventType(), claim.occurredOn(),
                SemanticCandidateMatcher.MAX_INTERVAL_DAYS, naturalKey,
                SemanticCandidateMatcher.MAX_CANDIDATES);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        SemanticCandidateMatcher.Query query = new SemanticCandidateMatcher.Query(
                claim.nodeCode(), claim.eventType(), claim.occurredOn(), claim.actor(), quote);
        List<SemanticCandidateMatcher.Candidate> inputs = candidates.stream()
                .map(candidate -> new SemanticCandidateMatcher.Candidate(
                        candidate.eventId(), claim.nodeCode(), claim.eventType(),
                        candidate.occurredOn(), candidate.actor(), candidate.evidenceQuote()))
                .toList();
        return matcher.match(query, inputs);
    }

    private static boolean hasEnoughTokens(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int tokens = 0;
        for (String token : text.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens++;
            }
        }
        return tokens >= MIN_SEMANTIC_TOKENS;
    }

    static String naturalKey(ClassificationRow claim) {
        return naturalKey(claim.nodeCode(), claim.eventType(), claim.actor(), claim.occurredOn());
    }

    public static String naturalKey(String nodeCode, String eventType, String actor, java.time.LocalDate occurredOn) {
        long weekBucket = Math.floorDiv(occurredOn.toEpochDay(), 7);
        return nodeCode + "|" + eventType + "|" + normalizeActor(actor) + "|" + weekBucket;
    }

    @Scheduled(cron = "${tracker.merge-cron:0 50 * * * *}")
    @SchedulerLock(name = "tracker-event-merger", lockAtLeastFor = "PT1M")
    public void runOnce() {
        for (ClassificationRow claim : repository.findUnmergedClassifications(BATCH_LIMIT)) {
            try {
                mergeClaim(claim);
            } catch (RuntimeException e) {
                log.warn("tracker event merge failed for classification {}: {}", claim.id(), e.toString());
            }
        }
    }

    private static String normalizeActor(String actor) {
        return actor == null ? "" : actor.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

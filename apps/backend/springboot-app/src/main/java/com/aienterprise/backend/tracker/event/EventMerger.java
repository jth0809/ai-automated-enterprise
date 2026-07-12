package com.aienterprise.backend.tracker.event;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.ClassificationRow;
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class EventMerger {

    private static final Logger log = LoggerFactory.getLogger(EventMerger.class);
    private static final int PROVISIONAL_TTL_DAYS = 90;
    private static final int BATCH_LIMIT = 200;

    private final TrackerRepository repository;

    public EventMerger(TrackerRepository repository) {
        this.repository = repository;
    }

    public long mergeClaim(ClassificationRow claim) {
        NodeRow node = repository.findNodeByCode(claim.nodeCode());
        EventRow draft = EventRow.draft(
                node.id(), claim.eventType(), claim.claimedLevel(), claim.actor(),
                claim.occurredOn(), VerificationLevel.CLAIMED.name(),
                LocalDate.now(ZoneOffset.UTC).plusDays(PROVISIONAL_TTL_DAYS),
                claim.rubricVersionId());
        long eventId = repository.upsertEventByNaturalKey(naturalKey(claim), draft);
        repository.linkClassification(claim.id(), eventId);
        VerificationLevel derived = VerificationDeriver.derive(repository.findClusterEvidence(eventId));
        repository.updateEventVerification(eventId, derived.name());
        return eventId;
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

package com.aienterprise.backend.tracker.ops;

import java.time.Clock;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.OpsActionDraft;
import com.aienterprise.backend.tracker.domain.OpsState;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class StateFreezeService {

    public static final String STATE_FROZEN_KEY = "STATE_FROZEN";
    public static final String FREEZE_REASON_KEY = "FREEZE_REASON";
    public static final String FREEZE_TRIGGER_KEY = "FREEZE_TRIGGER";
    public static final String FREEZE_AT_KEY = "FREEZE_AT";

    private static final int MAX_REASON_LENGTH = 2_000;
    private static final int MAX_REVIEW_NOTE_LENGTH = 2_000;
    private static final String RELEASE_NOTE_PREFIX = "Freeze released after human review: ";

    private final TrackerRepository repository;
    private final Clock clock;

    @Autowired
    public StateFreezeService(TrackerRepository repository) {
        this(repository, Clock.systemUTC());
    }

    StateFreezeService(TrackerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Missing state means the breaker has never fired. Any read failure or
     * malformed persisted value fails closed to protect node state writes.
     */
    public boolean isFrozen() {
        try {
            OpsState state = repository.findOpsState(STATE_FROZEN_KEY).orElse(null);
            if (state == null) {
                return false;
            }
            String value = state.value();
            if (value == null) {
                return true;
            }
            return !"false".equals(value.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException unavailableStateStore) {
            return true;
        }
    }

    @Transactional
    public boolean freeze(String reason, Trigger trigger) {
        String normalizedReason = validateReason(reason);
        if (trigger == null) {
            throw new IllegalArgumentException("freeze trigger is required");
        }
        if (!repository.markStateFrozenIfActive(STATE_FROZEN_KEY)) {
            return false;
        }
        repository.putOpsState(FREEZE_REASON_KEY, normalizedReason);
        repository.putOpsState(FREEZE_TRIGGER_KEY, trigger.name());
        repository.putOpsState(FREEZE_AT_KEY, clock.instant().toString());
        repository.insertOpsAction(new OpsActionDraft(
                "FREEZE", normalizedReason, trigger.name(), "ACTIVE", "FROZEN"));
        return true;
    }

    @Transactional
    public boolean release(String reason, Trigger trigger) {
        String normalizedReason = validateReason(reason);
        if (trigger != Trigger.HUMAN) {
            throw new IllegalArgumentException("state release requires a human trigger");
        }
        if (!repository.markStateReleasedIfFrozen(STATE_FROZEN_KEY)) {
            return false;
        }
        repository.resolvePendingCircuitBreakerReviews(releaseReviewNote(normalizedReason));
        repository.deleteOpsState(FREEZE_REASON_KEY);
        repository.deleteOpsState(FREEZE_TRIGGER_KEY);
        repository.deleteOpsState(FREEZE_AT_KEY);
        repository.insertOpsAction(new OpsActionDraft(
                "RELEASE", normalizedReason, trigger.name(), "FROZEN", "ACTIVE"));
        return true;
    }

    private static String validateReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "state transition reason must contain 1..2000 characters");
        }
        return normalized;
    }

    private static String releaseReviewNote(String reason) {
        int available = MAX_REVIEW_NOTE_LENGTH - RELEASE_NOTE_PREFIX.length();
        int end = Math.min(reason.length(), available);
        if (end > 0 && end < reason.length()
                && Character.isHighSurrogate(reason.charAt(end - 1))
                && Character.isLowSurrogate(reason.charAt(end))) {
            end--;
        }
        return RELEASE_NOTE_PREFIX + reason.substring(0, end);
    }

    public enum Trigger {
        AUTOMATIC,
        HUMAN,
        DRILL
    }
}

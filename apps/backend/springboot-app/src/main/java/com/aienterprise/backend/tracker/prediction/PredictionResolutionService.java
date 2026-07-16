package com.aienterprise.backend.tracker.prediction;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Resolves matured immutable predicates without treating absence as VOID. */
@Service
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class PredictionResolutionService {

    private final PredictionRepository repository;
    private final Clock clock;

    @Autowired
    public PredictionResolutionService(PredictionRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PredictionResolutionService(
            PredictionRepository repository,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Summary resolveDue() {
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        int attempted = 0;
        int hits = 0;
        int misses = 0;
        int reused = 0;
        int conflicts = 0;
        for (PredictionRepository.PendingPrediction pending
                : repository.findPendingDue(today)) {
            attempted++;
            PredictionRepository.ResolutionDraft draft = repository
                    .findFirstTargetTransition(pending)
                    .map(transition -> PredictionRepository.ResolutionDraft.hit(
                            pending.id(), transition.eventId(), pending.dueOn(), now))
                    .orElseGet(() -> PredictionRepository.ResolutionDraft.miss(
                            pending.id(), pending.dueOn(), now));
            PredictionRepository.ResolutionResult result = repository.resolve(draft);
            if (result.status() == PredictionRepository.ResolutionStatus.REUSED) {
                reused++;
            } else if (result.status()
                    == PredictionRepository.ResolutionStatus.CONFLICT) {
                conflicts++;
            } else if (result.outcome() == PredictionRepository.Outcome.HIT) {
                hits++;
            } else if (result.outcome() == PredictionRepository.Outcome.MISS) {
                misses++;
            }
        }
        return new Summary(attempted, hits, misses, reused, conflicts);
    }

    public PredictionRepository.ResolutionResult voidUnadjudicable(
            long predictionId,
            String auditNote) {
        if (auditNote == null || auditNote.isBlank()) {
            throw new IllegalArgumentException(
                    "manual VOID requires an explicit unadjudicable-predicate audit");
        }
        PredictionRepository.PendingPrediction pending = repository
                .findForResolution(predictionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown pending prediction"));
        return repository.resolve(PredictionRepository.ResolutionDraft.voided(
                pending.id(), LocalDate.now(clock), clock.instant(), auditNote));
    }

    public record Summary(
            int attempted,
            int hits,
            int misses,
            int reused,
            int conflicts) {

        public Summary {
            if (attempted < 0 || hits < 0 || misses < 0
                    || reused < 0 || conflicts < 0
                    || hits + misses + reused + conflicts != attempted) {
                throw new IllegalArgumentException("invalid resolution summary");
            }
        }
    }
}

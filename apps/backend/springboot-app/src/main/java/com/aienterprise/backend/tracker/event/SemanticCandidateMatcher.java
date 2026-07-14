package com.aienterprise.backend.tracker.event;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Safe, dependency-free second pass that links a candidate event to an existing
 * one only when text similarity is unambiguous. It never overrides an exact
 * natural-key match (that decision belongs to {@code EventMerger}); it is the
 * fallback used when no exact key exists.
 *
 * <p>Guards, all failing toward <em>not</em> merging: same node, exact event
 * type, occurred-on within {@value #MAX_INTERVAL_DAYS} days, compatible actors,
 * cosine at least {@value #COSINE_THRESHOLD}, and a top1-top2 margin of at least
 * {@value #MARGIN_THRESHOLD}. Ambiguity, actor conflict, or an empty candidate
 * set yields no match.
 */
public final class SemanticCandidateMatcher {

    public static final double COSINE_THRESHOLD = 0.82;
    public static final double MARGIN_THRESHOLD = 0.02;
    public static final int MAX_INTERVAL_DAYS = 7;
    public static final int MAX_CANDIDATES = 50;
    public static final String EMBEDDING_VERSION = TextFeatureEmbedding.VERSION;

    private static final Set<String> ACTOR_STOPWORDS = Set.of("and", "the", "of", "a");

    private final TextEmbedder embedder;

    public SemanticCandidateMatcher() {
        this(new TextFeatureEmbedding());
    }

    public SemanticCandidateMatcher(TextEmbedder embedder) {
        this.embedder = embedder;
    }

    /** The incoming event we are trying to place. */
    public record Query(
            String nodeCode, String eventType, LocalDate occurredOn, String actor, String text) {
    }

    /** An existing event that could receive the incoming evidence. */
    public record Candidate(
            long eventId, String nodeCode, String eventType, LocalDate occurredOn,
            String actor, String text) {
    }

    /**
     * Returns the event id of a single unambiguous match, or empty. The caller
     * must bound {@code candidates} to {@link #MAX_CANDIDATES}; a larger set is
     * a contract violation and fails fast rather than guessing.
     */
    public Optional<Long> match(Query query, List<Candidate> candidates) {
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "candidate set exceeds " + MAX_CANDIDATES + " entries");
        }
        double[] queryVector = embedder.embed(query.text());
        double bestScore = Double.NEGATIVE_INFINITY;
        double secondScore = Double.NEGATIVE_INFINITY;
        long bestId = -1L;
        for (Candidate candidate : candidates) {
            if (!eligible(query, candidate)) {
                continue;
            }
            double score = cosine(queryVector, embedder.embed(candidate.text()));
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestId = candidate.eventId();
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (bestId < 0 || bestScore < COSINE_THRESHOLD) {
            return Optional.empty();
        }
        if (secondScore != Double.NEGATIVE_INFINITY
                && bestScore - secondScore < MARGIN_THRESHOLD) {
            return Optional.empty();
        }
        return Optional.of(bestId);
    }

    private static boolean eligible(Query query, Candidate candidate) {
        if (!query.nodeCode().equals(candidate.nodeCode())) {
            return false;
        }
        if (!query.eventType().equals(candidate.eventType())) {
            return false;
        }
        long days = Math.abs(ChronoUnit.DAYS.between(query.occurredOn(), candidate.occurredOn()));
        if (days > MAX_INTERVAL_DAYS) {
            return false;
        }
        return actorsCompatible(query.actor(), candidate.actor());
    }

    private static boolean actorsCompatible(String left, String right) {
        Set<String> leftTokens = actorTokens(left);
        Set<String> rightTokens = actorTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return true;
        }
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> actorTokens(String actor) {
        Set<String> tokens = new HashSet<>();
        if (actor == null) {
            return tokens;
        }
        for (String token : actor.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isBlank() && !ACTOR_STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }
}

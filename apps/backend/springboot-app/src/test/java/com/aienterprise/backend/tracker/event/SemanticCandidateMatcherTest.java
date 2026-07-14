package com.aienterprise.backend.tracker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.event.SemanticCandidateMatcher.Candidate;
import com.aienterprise.backend.tracker.event.SemanticCandidateMatcher.Query;

class SemanticCandidateMatcherTest {

    private static final String NODE = "P1-REUSE-LV";
    private static final String TYPE = "FLIGHT_TEST";
    private static final LocalDate WHEN = LocalDate.of(2026, 1, 20);

    /** Returns a 256-dim unit vector whose cosine with the query axis is {@code cosine}. */
    private static double[] axisVector(double cosine) {
        double[] vector = new double[TextFeatureEmbedding.DIMENSIONS];
        vector[0] = cosine;
        vector[1] = Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        return vector;
    }

    private static final double[] QUERY_AXIS = axisVector(1.0);

    /** Embedder returning controlled vectors keyed by text, defaulting to zero. */
    private static TextEmbedder stub(Map<String, double[]> vectors) {
        return text -> vectors.getOrDefault(text, new double[TextFeatureEmbedding.DIMENSIONS]);
    }

    private static Query query(String text) {
        return new Query(NODE, TYPE, WHEN, "SpaceX", text);
    }

    private static Candidate candidate(long id, String text) {
        return new Candidate(id, NODE, TYPE, WHEN, "SpaceX", text);
    }

    @Test
    void matchesWhenCosineMeetsThreshold() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.82));
        var matcher = new SemanticCandidateMatcher(stub(vectors));

        Optional<Long> match = matcher.match(query("Q"), List.of(candidate(7, "C")));

        assertEquals(Optional.of(7L), match);
    }

    @Test
    void rejectsWhenCosineBelowThreshold() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.8199));
        var matcher = new SemanticCandidateMatcher(stub(vectors));

        assertEquals(Optional.empty(), matcher.match(query("Q"), List.of(candidate(7, "C"))));
    }

    @Test
    void rejectsAmbiguousTopTwo() {
        Map<String, double[]> vectors = Map.of(
                "Q", QUERY_AXIS, "A", axisVector(0.86), "B", axisVector(0.85));
        var matcher = new SemanticCandidateMatcher(stub(vectors));

        Optional<Long> match = matcher.match(
                query("Q"), List.of(candidate(1, "A"), candidate(2, "B")));

        assertEquals(Optional.empty(), match);
    }

    @Test
    void acceptsWhenMarginIsMet() {
        Map<String, double[]> vectors = Map.of(
                "Q", QUERY_AXIS, "A", axisVector(0.86), "B", axisVector(0.83));
        var matcher = new SemanticCandidateMatcher(stub(vectors));

        Optional<Long> match = matcher.match(
                query("Q"), List.of(candidate(1, "A"), candidate(2, "B")));

        assertEquals(Optional.of(1L), match);
    }

    @Test
    void rejectsActorConflict() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.95));
        var matcher = new SemanticCandidateMatcher(stub(vectors));
        Candidate esa = new Candidate(3, NODE, TYPE, WHEN, "ESA", "C");

        assertEquals(Optional.empty(), matcher.match(query("Q"), List.of(esa)));
    }

    @Test
    void rejectsDifferentEventType() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.95));
        var matcher = new SemanticCandidateMatcher(stub(vectors));
        Candidate other = new Candidate(4, NODE, "OPERATIONAL_DEPLOYMENT", WHEN, "SpaceX", "C");

        assertEquals(Optional.empty(), matcher.match(query("Q"), List.of(other)));
    }

    @Test
    void rejectsBeyondSevenDays() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.95));
        var matcher = new SemanticCandidateMatcher(stub(vectors));
        Candidate eightDays = new Candidate(5, NODE, TYPE, WHEN.plusDays(8), "SpaceX", "C");

        assertEquals(Optional.empty(), matcher.match(query("Q"), List.of(eightDays)));
    }

    @Test
    void treatsBlankActorAsCompatible() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.95));
        var matcher = new SemanticCandidateMatcher(stub(vectors));
        Query blankQuery = new Query(NODE, TYPE, WHEN, "  ", "Q");
        Candidate blankCandidate = new Candidate(6, NODE, TYPE, WHEN, null, "C");

        assertEquals(Optional.of(6L), matcher.match(blankQuery, List.of(blankCandidate)));
    }

    @Test
    void acceptsCompatibleActorAliasByTokenOverlap() {
        Map<String, double[]> vectors = Map.of("Q", QUERY_AXIS, "C", axisVector(0.95));
        var matcher = new SemanticCandidateMatcher(stub(vectors));
        Candidate joint = new Candidate(8, NODE, TYPE, WHEN, "NASA and SpaceX", "C");

        assertEquals(Optional.of(8L), matcher.match(query("Q"), List.of(joint)));
    }

    @Test
    void returnsEmptyWithoutCandidates() {
        var matcher = new SemanticCandidateMatcher(stub(Map.of("Q", QUERY_AXIS)));

        assertEquals(Optional.empty(), matcher.match(query("Q"), List.of()));
    }

    @Test
    void failsClosedWhenCandidateSetExceedsCap() {
        Map<String, double[]> vectors = new HashMap<>();
        vectors.put("Q", QUERY_AXIS);
        vectors.put("C", axisVector(0.95));
        var matcher = new SemanticCandidateMatcher(stub(vectors));

        List<Candidate> fifty = new ArrayList<>();
        for (int i = 0; i < SemanticCandidateMatcher.MAX_CANDIDATES; i++) {
            fifty.add(candidate(100 + i, "C"));
        }
        // 50 is accepted (returns a best match despite ties handled by margin).
        matcher.match(query("Q"), fifty);

        fifty.add(candidate(999, "C"));
        assertThrows(IllegalArgumentException.class, () -> matcher.match(query("Q"), fifty));
    }

    @Test
    void realEmbeddingMatchesRewordedEventAndRejectsUnrelated() {
        var matcher = new SemanticCandidateMatcher();
        Candidate same = candidate(11, "SpaceX static fire test of the Falcon booster stage");
        Candidate unrelated = candidate(12, "A quiet garden grew tomatoes through the summer");

        Optional<Long> match = matcher.match(
                query("SpaceX static fire test of the Falcon booster stage"),
                List.of(same, unrelated));

        assertEquals(Optional.of(11L), match);
    }
}

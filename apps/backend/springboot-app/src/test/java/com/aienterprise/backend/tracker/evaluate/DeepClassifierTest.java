package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.domain.ClassificationRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@ExtendWith(MockitoExtension.class)
class DeepClassifierTest {

    private static final String BODY = """
            NASA and SpaceX completed an orbital propellant transfer demonstration.
            The agency confirmed 10 tonnes of cryogenic propellant were moved between tanks.""";

    @Mock
    private AnthropicClient client;

    @Mock
    private CostGuard costGuard;

    @Mock
    private TrackerRepository repository;

    private DeepClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DeepClassifier(client, costGuard, repository, "classify-model");
    }

    @Test
    void validClaimIsPersistedVerifiedAndArticleClassified() {
        when(costGuard.allow()).thenReturn(true);
        when(repository.findRecentNaturalKeys(anyInt()))
                .thenReturn(List.of("P1-ORBIT-REFUEL|FLIGHT_TEST|spacex|2950"));
        when(repository.nodeCodeExists("P1-ORBIT-REFUEL")).thenReturn(true);
        when(repository.activeRubricVersionId()).thenReturn(7L);
        // Quote spans the body's line break (newline vs space): must still
        // verify after whitespace normalization.
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of(claim(
                        "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6,
                        "propellant transfer demonstration. The agency confirmed 10 tonnes"))));

        classifier.process(article());

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).completeWithTool(
                eq("classify-model"), anyList(), user.capture(), anyMap(), eq("classify_article"));
        assertTrue(user.getValue().contains("P1-ORBIT-REFUEL|FLIGHT_TEST|spacex|2950"));
        assertTrue(user.getValue().contains("Orbital refueling milestone"));

        ArgumentCaptor<ClassificationRow> row = ArgumentCaptor.forClass(ClassificationRow.class);
        verify(repository).insertClassification(row.capture());
        assertEquals(42, row.getValue().articleId());
        assertEquals("P1-ORBIT-REFUEL", row.getValue().nodeCode());
        assertEquals("FLIGHT_TEST", row.getValue().eventType());
        assertEquals(6, row.getValue().claimedLevel());
        assertEquals("SpaceX", row.getValue().actor());
        assertEquals(LocalDate.of(2026, 7, 1), row.getValue().occurredOn());
        assertEquals("THIRD_PARTY", row.getValue().publicationPath());
        assertTrue(row.getValue().quoteVerified());
        assertEquals(7L, row.getValue().rubricVersionId());
        verify(repository).updateArticleStatus(42, "CLASSIFIED");
    }

    @Test
    void quoteMismatchIsRecordedUnverifiedAndCreatesNoEvent() {
        stubHappyPipeline();
        when(repository.nodeCodeExists("P1-ORBIT-REFUEL")).thenReturn(true);
        when(repository.activeRubricVersionId()).thenReturn(7L);
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of(claim(
                        "P1-ORBIT-REFUEL", "FLIGHT_TEST", 6,
                        "This sentence never appeared in the article body."))));

        classifier.process(article());

        ArgumentCaptor<ClassificationRow> row = ArgumentCaptor.forClass(ClassificationRow.class);
        verify(repository).insertClassification(row.capture());
        assertFalse(row.getValue().quoteVerified());
        verify(repository, never()).upsertEventByNaturalKey(anyString(), any());
        verify(repository).updateArticleStatus(42, "CLASSIFIED");
    }

    @Test
    void emptyClaimListClassifiesArticleWithoutRows() {
        stubHappyPipeline();
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of()));

        classifier.process(article());

        verify(repository, never()).insertClassification(any());
        verify(repository).updateArticleStatus(42, "CLASSIFIED");
    }

    @Test
    void unregisteredNodeCodeIsSkippedWithoutRow() {
        stubHappyPipeline();
        when(repository.nodeCodeExists("P9-UNKNOWN")).thenReturn(false);
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of(claim(
                        "P9-UNKNOWN", "FLIGHT_TEST", 6, BODY))));

        classifier.process(article());

        verify(repository, never()).insertClassification(any());
        verify(repository).updateArticleStatus(42, "CLASSIFIED");
    }

    @Test
    void unknownEventTypeIsSkippedWithoutRow() {
        stubHappyPipeline();
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of(claim(
                        "P1-ORBIT-REFUEL", "PRESS_TOUR", 6, BODY))));

        classifier.process(article());

        verify(repository, never()).insertClassification(any());
        verify(repository).updateArticleStatus(42, "CLASSIFIED");
    }

    @Test
    void outOfRangeClaimedLevelIsSkippedWithoutRow() {
        stubHappyPipeline();
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of(claim(
                        "P1-ORBIT-REFUEL", "FLIGHT_TEST", 12, BODY))));

        classifier.process(article());

        verify(repository, never()).insertClassification(any());
        verify(repository).updateArticleStatus(42, "CLASSIFIED");
    }

    @Test
    void levelFreeSetbackClaimIsValid() {
        stubHappyPipeline();
        when(repository.nodeCodeExists("P1-ORBIT-REFUEL")).thenReturn(true);
        when(repository.activeRubricVersionId()).thenReturn(7L);
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("relevant_claims", List.of(claim(
                        "P1-ORBIT-REFUEL", "SETBACK", null,
                        "NASA and SpaceX completed an orbital propellant transfer demonstration."))));

        classifier.process(article());

        ArgumentCaptor<ClassificationRow> row = ArgumentCaptor.forClass(ClassificationRow.class);
        verify(repository).insertClassification(row.capture());
        assertNull(row.getValue().claimedLevel());
        assertTrue(row.getValue().quoteVerified());
    }

    @Test
    void exhaustedCostGuardLeavesArticleInGatePassedState() {
        when(costGuard.allow()).thenReturn(false);

        classifier.process(article());

        verify(client, never()).completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString());
        verify(repository, never()).updateArticleStatus(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    private void stubHappyPipeline() {
        when(costGuard.allow()).thenReturn(true);
        when(repository.findRecentNaturalKeys(anyInt())).thenReturn(List.of());
    }

    private static java.util.HashMap<String, Object> claim(
            String nodeCode, String eventType, Integer level, String quote) {
        var map = new java.util.HashMap<String, Object>();
        map.put("node_code", nodeCode);
        map.put("event_type", eventType);
        map.put("claimed_level", level);
        map.put("actor", "SpaceX");
        map.put("occurred_on", "2026-07-01");
        map.put("publication_path", "THIRD_PARTY");
        map.put("evidence_quote", quote);
        map.put("duplicate_hint", "NEW");
        return map;
    }

    private ArticleRow article() {
        return new ArticleRow(
                42, 1, "https://example.test/a", "a".repeat(64), "Orbital refueling milestone",
                Instant.parse("2026-07-12T00:00:00Z"), Instant.parse("2026-07-12T01:00:00Z"),
                BODY, false, "GATE_PASSED", 0);
    }
}

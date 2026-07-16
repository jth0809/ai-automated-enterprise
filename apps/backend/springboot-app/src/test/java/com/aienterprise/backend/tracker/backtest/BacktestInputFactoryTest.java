package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.aienterprise.backend.tracker.backfill.BackfillDatasetFingerprint;
import com.aienterprise.backend.tracker.domain.BackfillImportRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.graph.CapabilityGraphRepository;
import com.aienterprise.backend.tracker.math.ModelParameterRepository;
import com.aienterprise.backend.tracker.math.TrendFeatureRepository;

class BacktestInputFactoryTest {

    private static final String DATASET_VERSION = "backfill-v1";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-16T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void createsAReviewedCutoffLocalInputFromTheImportedCorpus() {
        Fixture fixture = fixture();

        BacktestHarness.Input input = fixture.factory().create();

        assertEquals(147, input.claims().size());
        assertEquals(fixture.datasetHash(),
                input.descriptor().datasetSha256());
        assertEquals(1_000, input.descriptor().sampleCount());
        assertEquals(LocalDate.of(2026, 1, 5), input.descriptor().schedule()
                .holdout().folds().getLast().target());
        assertEquals("params-v2", input.descriptor().paramsVersion());
        assertEquals("graph-v1.0", input.descriptor().graphVersion());
    }

    @Test
    void refusesAResourceWhoseHashDoesNotMatchTheAuditImport() {
        Fixture fixture = fixture("f".repeat(64), 147);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, fixture.factory()::create);

        assertEquals("backtest corpus hash does not match its import",
                error.getMessage());
    }

    @Test
    void refusesAResourceWhoseReviewedClaimCountChanged() {
        Fixture fixture = fixture(fingerprint(), 146);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, fixture.factory()::create);

        assertEquals("backtest corpus claim count does not match its import",
                error.getMessage());
    }

    private static Fixture fixture() {
        return fixture(fingerprint(), 147);
    }

    private static Fixture fixture(String importedHash, int recordCount) {
        TrackerRepository tracker = mock(TrackerRepository.class);
        CapabilityGraphRepository graphs = mock(CapabilityGraphRepository.class);
        ModelParameterRepository parameters = mock(ModelParameterRepository.class);
        TrendFeatureRepository trends = mock(TrendFeatureRepository.class);
        var candidates = new ClassPathResource(
                "tracker/historical-candidates-v1.jsonl");
        var mappings = new ClassPathResource("tracker/backfill-v1.json");
        when(tracker.findBackfillImport(DATASET_VERSION)).thenReturn(Optional.of(
                new BackfillImportRow(DATASET_VERSION, importedHash,
                        "nodes-v1.0", 2L, Instant.EPOCH, recordCount)));
        when(tracker.findAllNodes()).thenReturn(BacktestTestFixtures.nodes());
        when(graphs.loadActive()).thenReturn(BacktestTestFixtures.graph());
        when(parameters.loadActive()).thenReturn(BacktestTestFixtures.model());
        when(trends.findApprovedBreaks(
                LocalDate.of(2026, 7, 13), "params-v2"))
                .thenReturn(List.of());
        BacktestInputFactory factory = new BacktestInputFactory(
                tracker, graphs, parameters, trends, candidates, mappings,
                DATASET_VERSION, FIXED_CLOCK);
        return new Fixture(factory, importedHash);
    }

    private static String fingerprint() {
        return BackfillDatasetFingerprint.sha256(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
                new ClassPathResource("tracker/backfill-v1.json"),
                DATASET_VERSION, "nodes-v1.0", "r2.0");
    }

    private record Fixture(BacktestInputFactory factory, String datasetHash) {
    }
}

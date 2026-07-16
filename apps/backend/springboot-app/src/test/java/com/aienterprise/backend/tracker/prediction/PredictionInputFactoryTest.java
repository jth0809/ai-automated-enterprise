package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.aienterprise.backend.tracker.backfill.BackfillDatasetFingerprint;
import com.aienterprise.backend.tracker.domain.BackfillImportRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.math.ModelParameterRepository;
import com.aienterprise.backend.tracker.math.ModelParameters;
import com.aienterprise.backend.tracker.math.Params;

class PredictionInputFactoryTest {

    private static final String DATASET_VERSION = "backfill-v1";
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-16T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void createsAReviewedNetworkFreeIssuanceInput() {
        Fixture fixture = fixture(fingerprint(), 147);

        PredictionInputFactory.Input input = fixture.factory().create();

        assertEquals(147, input.claims().size());
        assertEquals(fixture.datasetHash(), input.datasetSha256());
        assertEquals(LocalDate.of(2026, 7, 13), input.asOf());
        assertEquals(LocalDate.of(2026, 7, 16), input.issuedOn());
        assertEquals("hazard-v1", input.hazardParameters().version());
        assertEquals(15, input.dormancyTriggerYears());
    }

    @Test
    void refusesHashOrCountDriftFromTheImportedAudit() {
        assertEquals("prediction corpus hash does not match its import",
                assertThrows(IllegalStateException.class,
                        fixture("f".repeat(64), 147).factory()::create)
                        .getMessage());
        assertEquals("prediction corpus claim count does not match its import",
                assertThrows(IllegalStateException.class,
                        fixture(fingerprint(), 146).factory()::create)
                        .getMessage());
    }

    private static Fixture fixture(String importedHash, int recordCount) {
        TrackerRepository tracker = mock(TrackerRepository.class);
        ModelParameterRepository model = mock(ModelParameterRepository.class);
        PredictionRepository predictions = mock(PredictionRepository.class);
        var candidates = new ClassPathResource(
                "tracker/historical-candidates-v1.jsonl");
        var mappings = new ClassPathResource("tracker/backfill-v1.json");
        when(tracker.findBackfillImport(DATASET_VERSION)).thenReturn(Optional.of(
                new BackfillImportRow(DATASET_VERSION, importedHash,
                        "nodes-v1.0", 2L, Instant.EPOCH, recordCount)));
        when(tracker.findAllNodes()).thenReturn(List.of(new NodeRow(
                1, "P1-REUSE-LV", 1, "완전 재사용 발사체", "TRL", 5,
                "OFFICIAL", "ACTIVE", null, null, 0.18, false,
                "fixture", "nodes-v1.0")));
        when(model.loadActive()).thenReturn(new ModelParameters(
                Params.defaults(), Map.of()));
        when(predictions.loadActiveParameters()).thenReturn(
                HazardParameters.defaults());
        PredictionInputFactory factory = new PredictionInputFactory(
                tracker, model, predictions, candidates, mappings,
                DATASET_VERSION, FIXED_CLOCK);
        return new Fixture(factory, importedHash);
    }

    private static String fingerprint() {
        return BackfillDatasetFingerprint.sha256(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"),
                new ClassPathResource("tracker/backfill-v1.json"),
                DATASET_VERSION, "nodes-v1.0", "r2.0");
    }

    private record Fixture(
            PredictionInputFactory factory,
            String datasetHash) {
    }
}

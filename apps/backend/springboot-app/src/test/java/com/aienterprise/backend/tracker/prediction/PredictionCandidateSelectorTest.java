package com.aienterprise.backend.tracker.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;

class PredictionCandidateSelectorTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 7, 16);

    @Test
    void choosesTheMostInformativeHorizonAndKeepsIntegrationIndependent() {
        HazardEstimator.NodeHazard integration = hazard(
                1, "P1-INTEGRATION", 1, 4, true, 0.40, 0.40);

        List<PredictionCandidateSelector.Candidate> selected =
                new PredictionCandidateSelector(HazardParameters.defaults())
                        .select(List.of(integration), ISSUED,
                                PredictionCandidateSelector.Calibration.identity());

        var candidate = selected.getFirst();
        assertEquals(18, candidate.horizonMonths());
        assertEquals(5, candidate.targetLevel());
        assertEquals(LocalDate.of(2028, 1, 16), candidate.dueOn());
        assertEquals("P1-INTEGRATION이 2028-01-16까지 검증된 수준 L5 이상에 도달한다.",
                candidate.statement());
        assertTrue(candidate.integrationNode());
    }

    @Test
    void prioritizesInformativeRowsButUsesLowInformationToFillCapacity() {
        List<HazardEstimator.NodeHazard> hazards = List.of(
                hazard(1, "P1-A", 1, 3, false, 0.35, 0.40),
                hazard(2, "P1-B", 1, 3, false, 0.30, 0.30),
                hazard(3, "P1-C", 1, 3, false, 0.90, 0.001));

        List<PredictionCandidateSelector.Candidate> selected =
                new PredictionCandidateSelector(HazardParameters.defaults())
                        .select(hazards, ISSUED,
                                PredictionCandidateSelector.Calibration.identity());

        assertEquals(List.of("P1-A", "P1-B"),
                selected.stream().map(PredictionCandidateSelector.Candidate::nodeCode)
                        .toList());
        assertTrue(selected.stream().allMatch(candidate ->
                candidate.informationStatus()
                        == PredictionCandidateSelector.InformationStatus.INFORMATIVE));

        List<PredictionCandidateSelector.Candidate> filled =
                new PredictionCandidateSelector(HazardParameters.defaults())
                        .select(List.of(hazards.getLast()), ISSUED,
                                PredictionCandidateSelector.Calibration.identity());
        assertEquals(PredictionCandidateSelector.InformationStatus.LOW_INFORMATION,
                filled.getFirst().informationStatus());
    }

    @Test
    void capsAtTwoPerPillarAndTwelvePerCohortWithStableTies() {
        List<HazardEstimator.NodeHazard> hazards = new ArrayList<>();
        long id = 1;
        for (int pillar = 1; pillar <= 6; pillar++) {
            hazards.add(hazard(id++, "P" + pillar + "-C", pillar,
                    2, false, 0.20, 0.30));
            hazards.add(hazard(id++, "P" + pillar + "-A", pillar,
                    2, false, 0.40, 0.30));
            hazards.add(hazard(id++, "P" + pillar + "-B", pillar,
                    2, false, 0.30, 0.30));
        }

        List<PredictionCandidateSelector.Candidate> selected =
                new PredictionCandidateSelector(HazardParameters.defaults())
                        .select(hazards, ISSUED,
                                PredictionCandidateSelector.Calibration.identity());

        assertEquals(12, selected.size());
        for (int pillar = 1; pillar <= 6; pillar++) {
            int current = pillar;
            assertEquals(2, selected.stream()
                    .filter(candidate -> candidate.pillar() == current).count());
            assertEquals(List.of("P" + pillar + "-A", "P" + pillar + "-B"),
                    selected.stream().filter(candidate -> candidate.pillar() == current)
                            .map(PredictionCandidateSelector.Candidate::nodeCode)
                            .toList());
        }
    }

    @Test
    void excludesOnlyIneligibleNodesAndPreservesRawAndCalibratedProbability() {
        HazardEstimator.NodeHazard eligible = hazard(
                1, "P3-A", 3, 7, false, 0.5, 0.3);
        HazardEstimator.NodeHazard levelEight = hazard(
                2, "P3-B", 3, 8, false, 0.5, 0.3);
        HazardEstimator.NodeHazard dormant = new HazardEstimator.NodeHazard(
                node(3, "P3-C", 3, 4, false, 0.5), 4, "DORMANT",
                1, 10, 0.1, 0.3, false);
        var calibration = new PredictionCandidateSelector.Calibration(
                "calibration-test", raw -> Math.min(0.98, raw + 0.05));

        List<PredictionCandidateSelector.Candidate> selected =
                new PredictionCandidateSelector(HazardParameters.defaults())
                        .select(List.of(levelEight, dormant, eligible), ISSUED,
                                calibration);

        assertEquals(1, selected.size());
        assertEquals("P3-A", selected.getFirst().nodeCode());
        assertEquals(selected.getFirst().rawProbability() + 0.05,
                selected.getFirst().calibratedProbability(), 1e-12);
        assertEquals("calibration-test", selected.getFirst().calibrationVersion());
    }

    private static HazardEstimator.NodeHazard hazard(
            long id,
            String code,
            int pillar,
            int level,
            boolean integration,
            double weight,
            double nodeRate) {
        return new HazardEstimator.NodeHazard(
                node(id, code, pillar, level, integration, weight),
                level, "ACTIVE", 2, 20, 0.1, nodeRate, level <= 7);
    }

    private static NodeRow node(
            long id,
            String code,
            int pillar,
            int level,
            boolean integration,
            double weight) {
        return new NodeRow(
                id, code, pillar, code, pillar == 6 ? "EGL" : "TRL", level,
                "OFFICIAL", "ACTIVE", null, null, weight, integration,
                "fixture", "nodes-v1.0");
    }
}

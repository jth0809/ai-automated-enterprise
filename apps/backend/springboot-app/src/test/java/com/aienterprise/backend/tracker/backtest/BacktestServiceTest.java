package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class BacktestServiceTest {

    @Test
    void calculatesStoresAndReturnsOneMatchingAuditReport() {
        BacktestHarness.Input input = BacktestTestFixtures.input(
                LocalDate.of(2011, 1, 3));
        BacktestReport report = BacktestTestFixtures.report();
        BacktestRepository repository = mock(BacktestRepository.class);
        BacktestInputFactory inputs = mock(BacktestInputFactory.class);
        BacktestRepository.StoredRun stored = stored(report);
        when(inputs.create()).thenReturn(input);
        when(repository.findCompletedByInputHash(report.inputSha256()))
                .thenReturn(java.util.Optional.empty());
        when(repository.saveCompleted(report)).thenReturn(stored);
        BacktestService service = new BacktestService(
                repository, inputs, ignored -> report);

        assertEquals(stored, service.run());
        verify(repository).saveCompleted(report);
    }

    @Test
    void reusesACompletedMatchingInputWithoutRecalculation() {
        BacktestHarness.Input input = BacktestTestFixtures.input(
                LocalDate.of(2011, 1, 3));
        BacktestReport report = BacktestTestFixtures.report();
        BacktestRepository repository = mock(BacktestRepository.class);
        BacktestInputFactory inputs = mock(BacktestInputFactory.class);
        BacktestRepository.StoredRun stored = stored(report);
        AtomicInteger calculations = new AtomicInteger();
        when(inputs.create()).thenReturn(input);
        when(repository.findCompletedByInputHash(report.inputSha256()))
                .thenReturn(java.util.Optional.of(stored));
        BacktestService service = new BacktestService(
                repository, inputs, ignored -> {
                    calculations.incrementAndGet();
                    return report;
                });

        assertEquals(stored, service.run());
        assertEquals(0, calculations.get());
        verify(repository, never()).saveCompleted(report);
    }

    @Test
    void calculationFailureNeverTouchesTheAuditRepository() {
        BacktestHarness.Input input = BacktestTestFixtures.input(
                LocalDate.of(2011, 1, 3));
        BacktestRepository repository = mock(BacktestRepository.class);
        BacktestInputFactory inputs = mock(BacktestInputFactory.class);
        when(inputs.create()).thenReturn(input);
        when(repository.findCompletedByInputHash(
                BacktestFingerprint.of(input.descriptor()).sha256()))
                .thenReturn(java.util.Optional.empty());
        BacktestService service = new BacktestService(
                repository, inputs, ignored -> {
                    throw new IllegalStateException("synthetic failure");
                });

        assertThrows(IllegalStateException.class, service::run);
        verify(repository, never()).saveCompleted(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesAReportCalculatedForAnotherInput() {
        BacktestHarness.Input input = BacktestTestFixtures.input(
                LocalDate.of(2011, 1, 3));
        BacktestReport foreign = copyWithInputHash(
                BacktestTestFixtures.report(), "f".repeat(64));
        BacktestRepository repository = mock(BacktestRepository.class);
        BacktestInputFactory inputs = mock(BacktestInputFactory.class);
        when(inputs.create()).thenReturn(input);
        BacktestService service = new BacktestService(
                repository, inputs, ignored -> foreign);

        assertThrows(IllegalStateException.class, service::run);
        verify(repository, never()).saveCompleted(foreign);
    }

    private static BacktestRepository.StoredRun stored(BacktestReport report) {
        BacktestReportCodec.Encoded encoded = new BacktestReportCodec().encode(report);
        return new BacktestRepository.StoredRun(
                1L, report, encoded.json(), encoded.sha256(),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static BacktestReport copyWithInputHash(
            BacktestReport report, String inputHash) {
        return new BacktestReport(
                report.reportVersion(), inputHash, report.seed(),
                report.datasetSha256(), report.nodeSetVersion(),
                report.rubricVersion(), report.paramsVersion(),
                report.graphVersion(), report.candidateRegistryVersion(),
                report.asOf(), report.calibrationStart(), report.calibrationEnd(),
                report.holdoutStart(), report.holdoutEnd(), report.horizonWeeks(),
                report.sampleCount(), report.calibrationCutoffCount(),
                report.holdoutCutoffCount(), report.selectedCandidate(),
                report.objectiveScore(), report.calibrationCandidates(),
                report.folds(), report.metrics(), report.modelEvaluations());
    }
}

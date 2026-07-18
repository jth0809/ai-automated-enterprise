package com.aienterprise.backend.tracker.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BacktestReportCodecTest {

    private final BacktestReportCodec codec = new BacktestReportCodec();

    @Test
    void reportEncodingIsByteStableAndRoundTrips() {
        BacktestReport report = BacktestTestFixtures.report();

        BacktestReportCodec.Encoded first = codec.encode(report);
        BacktestReportCodec.Encoded second = codec.encode(report);

        assertEquals(first, second);
        assertEquals(report, codec.decode(first.json(), first.sha256()));
        assertEquals(report.inputSha256(), first.inputSha256());
    }

    @Test
    void tamperedReportIsRejectedBeforeDeserialization() {
        BacktestReportCodec.Encoded encoded = codec.encode(
                BacktestTestFixtures.report());

        assertThrows(IllegalStateException.class,
                () -> codec.decode(encoded.json() + " ", encoded.sha256()));
    }

    @Test
    void legacyV1ReportRemainsReadableWithoutInventedEvaluations() {
        BacktestReport current = BacktestTestFixtures.report();
        BacktestReport legacy = new BacktestReport(
                "backtest-report-v1", current.inputSha256(), current.seed(),
                current.datasetSha256(), current.nodeSetVersion(),
                current.rubricVersion(), current.paramsVersion(),
                current.graphVersion(), "backtest-candidates-v1",
                current.asOf(), current.calibrationStart(),
                current.calibrationEnd(), current.holdoutStart(),
                current.holdoutEnd(), current.horizonWeeks(),
                current.sampleCount(), current.calibrationCutoffCount(),
                current.holdoutCutoffCount(), current.selectedCandidate(),
                current.objectiveScore(), current.calibrationCandidates(),
                current.folds(), current.metrics(), java.util.List.of());

        BacktestReportCodec.Encoded encoded = codec.encode(legacy);
        BacktestReport decoded = codec.decode(encoded.json(), encoded.sha256());

        assertEquals("backtest-report-v1", decoded.reportVersion());
        assertEquals("backtest-candidates-v1", decoded.candidateRegistryVersion());
        assertEquals(java.util.List.of(), decoded.modelEvaluations());
    }
}

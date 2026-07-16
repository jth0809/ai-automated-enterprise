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
}

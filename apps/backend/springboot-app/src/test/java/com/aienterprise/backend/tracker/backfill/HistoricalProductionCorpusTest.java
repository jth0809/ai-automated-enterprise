package com.aienterprise.backend.tracker.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class HistoricalProductionCorpusTest {

    @Test
    void productionCorpusIsStructurallyValid() {
        CorpusReport report = new HistoricalCorpusValidator().validate(
                new ClassPathResource("tracker/historical-candidates-v1.jsonl"));

        assertTrue(report.errors().isEmpty(), () -> String.join(System.lineSeparator(), report.errors()));
        assertTrue(report.totalCount() > 0);
        assertTrue(report.totalCount() <= 512);
        assertEquals(report.totalCount(), report.readyCount());
        assertEquals(0, report.rejectedCount());
        System.out.printf(
                "CORPUS_REPORT total=%d ready=%d rejected=%d errors=%d%n",
                report.totalCount(),
                report.readyCount(),
                report.rejectedCount(),
                report.errors().size());
    }
}

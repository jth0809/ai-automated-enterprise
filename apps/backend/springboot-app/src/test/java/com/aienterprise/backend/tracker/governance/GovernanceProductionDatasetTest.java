package com.aienterprise.backend.tracker.governance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GovernanceProductionDatasetTest {

    @Test
    void productionLedgerIsReviewedBoundedAndNotPinnedToOneCount() throws Exception {
        byte[] json = new ClassPathResource("tracker/governance-ledger-v1.json")
                .getContentAsByteArray();
        var dataset = new GovernanceLedgerValidator().validate(json, Clock.fixed(
                Instant.parse("2026-07-15T23:59:59Z"), ZoneOffset.UTC));

        assertTrue(dataset.errors().isEmpty(), () -> String.join("\n", dataset.errors()));
        assertTrue(dataset.records().size() >= 6 && dataset.records().size() <= 100);
        assertTrue(dataset.records().stream().map(GovernanceRecord::sourceCode)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of("UNOOSA", "FAA", "GOVINFO")));
    }
}

package com.aienterprise.backend.tracker.transport;

import java.time.LocalDate;

/** Base snapshot bounds and their optional read-time coherence overlay. */
public record EtaBounds(
        Double baseEtaLow,
        Double baseEtaHigh,
        Double etaLow,
        Double etaHigh,
        boolean coherenceAdjusted,
        LocalDate coherenceReportPeriod) {
}

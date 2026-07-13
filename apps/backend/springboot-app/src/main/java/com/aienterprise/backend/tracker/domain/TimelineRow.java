package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record TimelineRow(
        LocalDate occurredOn,
        String occurredOnPrecision,
        String nodeName,
        String eventType,
        Integer levelFrom,
        Integer levelTo,
        Double impactScore,
        String verificationLevel,
        int sourceCount,
        String evidenceQuote,
        ReviewEvidence primaryEvidence) {
}

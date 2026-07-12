package com.aienterprise.backend.tracker.domain;

import java.time.LocalDate;

public record NodeRow(
        long id,
        String code,
        int pillar,
        String nameKo,
        String scaleType,
        int currentLevel,
        String verificationLevel,
        String nodeStatus,
        LocalDate dormantSince,
        LocalDate programEndDate,
        double weight,
        boolean integrationNode,
        String description,
        String nodeSetVersion) {
}

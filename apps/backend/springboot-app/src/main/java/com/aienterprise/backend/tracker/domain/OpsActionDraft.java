package com.aienterprise.backend.tracker.domain;

public record OpsActionDraft(
        String actionType,
        String reason,
        String triggerType,
        String previousState,
        String newState) {
}

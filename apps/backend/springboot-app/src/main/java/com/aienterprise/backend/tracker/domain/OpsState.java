package com.aienterprise.backend.tracker.domain;

import java.time.Instant;

public record OpsState(String value, Instant updatedAt) {
}

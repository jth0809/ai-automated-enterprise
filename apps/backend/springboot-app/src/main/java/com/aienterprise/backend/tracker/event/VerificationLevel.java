package com.aienterprise.backend.tracker.event;

public enum VerificationLevel {
    CLAIMED,
    PEER_REVIEWED,
    OFFICIAL,
    INDEPENDENT;

    public boolean atLeast(VerificationLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }
}

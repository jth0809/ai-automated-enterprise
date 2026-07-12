package com.aienterprise.backend.tracker.event;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class VerificationDeriver {

    private VerificationDeriver() {
    }

    public static VerificationLevel derive(List<SourceEvidence> cluster) {
        VerificationLevel result = VerificationLevel.CLAIMED;
        Set<Long> independentReliableSources = new HashSet<>();

        for (SourceEvidence evidence : cluster) {
            if (evidence.tier() == 1 && equals(evidence.sourceType(), "AGENCY")) {
                result = max(result, VerificationLevel.OFFICIAL);
            }
            if (evidence.tier() == 1 && equals(evidence.sourceType(), "JOURNAL")) {
                result = max(result, VerificationLevel.PEER_REVIEWED);
            }
            if (evidence.tier() <= 2 && !equals(evidence.publicationPath(), "WIRE_REPRINT")) {
                independentReliableSources.add(evidence.sourceId());
            }
        }

        if (independentReliableSources.size() >= 2) {
            result = VerificationLevel.INDEPENDENT;
        }
        return result;
    }

    private static VerificationLevel max(VerificationLevel left, VerificationLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static boolean equals(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }
}

package com.aienterprise.backend.tracker.api;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Read-only diagnostics describing the evidence base, not readiness credit. */
public record EvidenceCoverage(
        int historicalCandidateCount,
        int approvedClaimCount,
        int distinctCandidatesUsed,
        int activeNodeCount,
        int directlyMappedActiveNodeCount,
        int singleEvidenceClaimCount,
        Map<String, Integer> verificationLevelCounts) {

    public EvidenceCoverage {
        if (historicalCandidateCount < 0
                || approvedClaimCount < 0
                || distinctCandidatesUsed < 0
                || activeNodeCount < 0
                || directlyMappedActiveNodeCount < 0
                || directlyMappedActiveNodeCount > activeNodeCount
                || singleEvidenceClaimCount < 0
                || singleEvidenceClaimCount > approvedClaimCount) {
            throw new IllegalArgumentException(
                    "evidence coverage counts must be internally consistent");
        }
        TreeMap<String, Integer> ordered = new TreeMap<>();
        verificationLevelCounts.forEach((level, count) -> {
            if (level == null || level.isBlank() || count == null || count < 0) {
                throw new IllegalArgumentException(
                        "verification coverage must contain non-negative counts");
            }
            ordered.put(level, count);
        });
        verificationLevelCounts = Collections.unmodifiableMap(ordered);
    }
}

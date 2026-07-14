package com.aienterprise.backend.tracker.backfill;

/**
 * Whether a historical cancellation closes the capability-wide representative
 * program lineage. Individual mission cancellations default to {@link #NONE}.
 */
public enum ProgramEndEffect {
    NONE,
    CAPABILITY_PROGRAM_END
}

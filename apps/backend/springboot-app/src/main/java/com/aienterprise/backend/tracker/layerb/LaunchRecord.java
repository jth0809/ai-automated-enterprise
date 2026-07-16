package com.aienterprise.backend.tracker.layerb;

import java.time.Instant;

/**
 * A single launch parsed from the Launch Library 2 feed. Only launch metadata
 * is kept -- no article body, quote, or binary. Used to derive Layer B cadence
 * measurements (counts, success rate), not to fabricate capability events.
 */
public record LaunchRecord(
        String id, String name, Instant net, String provider, String status,
        boolean successful, String vehicleConfiguration) {
}

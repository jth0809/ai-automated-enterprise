package com.aienterprise.backend.tracker.layerb;

import java.time.Instant;

/** A reviewed change in the worldwide orbital human population. */
public record HumanPresenceTransition(Instant timestampUtc, int orbitPopulation) {
}

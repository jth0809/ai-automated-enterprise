package com.aienterprise.backend.tracker.event;

import static com.aienterprise.backend.tracker.event.VerificationLevel.CLAIMED;
import static com.aienterprise.backend.tracker.event.VerificationLevel.INDEPENDENT;
import static com.aienterprise.backend.tracker.event.VerificationLevel.OFFICIAL;
import static com.aienterprise.backend.tracker.event.VerificationLevel.PEER_REVIEWED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class VerificationDeriverTest {

    @Test
    void agencyPrimarySourceIsOfficial() {
        assertEquals(OFFICIAL, VerificationDeriver.derive(List.of(ev(1, 1, "AGENCY", "PRIMARY"))));
    }

    @Test
    void tierOneJournalIsPeerReviewedNotOfficial() {
        assertEquals(PEER_REVIEWED, VerificationDeriver.derive(List.of(ev(2, 1, "JOURNAL", "PRIMARY"))));
    }

    @Test
    void twoIndependentTierTwoSourcesAreIndependent() {
        assertEquals(INDEPENDENT, VerificationDeriver.derive(List.of(
                ev(3, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"),
                ev(4, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"))));
    }

    @Test
    void wireReprintDoesNotCreateIndependentCorroboration() {
        assertEquals(CLAIMED, VerificationDeriver.derive(List.of(
                ev(3, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"),
                ev(5, 3, "GENERAL_MEDIA", "WIRE_REPRINT"))));
    }

    @Test
    void preprintAndCorporatePrimaryRemainClaimed() {
        assertEquals(CLAIMED, VerificationDeriver.derive(List.of(ev(6, 2, "PREPRINT", "PRIMARY"))));
        assertEquals(CLAIMED, VerificationDeriver.derive(List.of(ev(7, 3, "CORPORATE", "PRIMARY"))));
    }

    @Test
    void highestApplicableLevelWins() {
        assertEquals(INDEPENDENT, VerificationDeriver.derive(List.of(
                ev(1, 1, "AGENCY", "PRIMARY"),
                ev(3, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"),
                ev(4, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"))));
    }

    @Test
    void duplicateSourceIdsCountOnlyOnce() {
        assertEquals(CLAIMED, VerificationDeriver.derive(List.of(
                ev(3, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"),
                ev(3, 2, "SPECIALIZED_MEDIA", "THIRD_PARTY"))));
    }

    private SourceEvidence ev(long sourceId, int tier, String type, String path) {
        return new SourceEvidence(sourceId, tier, type, path);
    }
}

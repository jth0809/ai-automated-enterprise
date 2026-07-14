package com.aienterprise.backend.tracker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class FeedDeadmanRepositoryTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Test
    void readsOnlyActiveFeedsAndHardCapsRecentPublicationTimestamps() {
        long disabledSourceId = sourceId("NASA");
        long activeSourceId = sourceId("SPACENEWS");
        jdbc.sql("UPDATE source_registry SET feed_active = 'N' WHERE id = :id")
                .param("id", disabledSourceId)
                .update();
        repository.insertArticleIfNew(
                "https://www.nasa.gov/deadman-disabled",
                "f".repeat(64), disabledSourceId, "Disabled", Instant.parse("2026-07-14T00:00:00Z"),
                "Body").orElseThrow();

        Instant latest = Instant.parse("2026-07-14T12:00:00Z");
        for (int index = 0; index < 70; index++) {
            repository.insertArticleIfNew(
                    "https://spacenews.com/deadman-" + index,
                    String.format("%064x", 10_000 + index), activeSourceId,
                    "Publication " + index, latest.minus(index, ChronoUnit.HOURS), "Body")
                    .orElseThrow();
        }

        var windows = repository.findActiveFeedPublicationWindows(1_000);

        assertFalse(windows.stream().anyMatch(window -> window.sourceCode().equals("NASA")));
        FeedPublicationWindow active = windows.stream()
                .filter(window -> window.sourceCode().equals("SPACENEWS"))
                .findFirst().orElseThrow();
        assertEquals(64, active.publicationTimes().size());
        assertEquals(latest, active.publicationTimes().getFirst());
    }

    private long sourceId(String code) {
        return jdbc.sql("SELECT id FROM source_registry WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }
}

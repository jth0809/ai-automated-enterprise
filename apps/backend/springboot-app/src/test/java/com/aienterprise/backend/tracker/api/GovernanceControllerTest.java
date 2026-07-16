package com.aienterprise.backend.tracker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.governance.GovernanceRecord;
import com.aienterprise.backend.tracker.governance.GovernanceRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class GovernanceControllerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private GovernanceRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new GovernanceController(repository, CLOCK)).build();
    }

    @Test
    void emptyLedgerReturnsHonestInsufficientResponse() throws Exception {
        mvc.perform(get("/api/tracker/governance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.datasetVersion").doesNotExist())
                .andExpect(jsonPath("$.recordCount").value(0))
                .andExpect(jsonPath("$.records").isEmpty())
                .andExpect(jsonPath("$.readiness").doesNotExist())
                .andExpect(jsonPath("$.etaYear").doesNotExist());
    }

    @Test
    void currentLedgerExposesReviewedProvenanceWithoutScoringFields() throws Exception {
        insert(0);
        repository.recordImport("governance-test-v1", "a".repeat(64), 1);

        mvc.perform(get("/api/tracker/governance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.datasetVersion").value("governance-test-v1"))
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.latestEffectiveOn").value("2026-01-01"))
                .andExpect(jsonPath("$.records[0].sourceCode").value("UNOOSA"))
                .andExpect(jsonPath("$.records[0].publicationPath").value("PRIMARY"))
                .andExpect(jsonPath("$.records[0].reviewStatus").value("HUMAN_REVIEWED"))
                .andExpect(jsonPath("$.records[0].contentSha256").value("1".repeat(64)))
                .andExpect(jsonPath("$.records[0].nodeCode").doesNotExist())
                .andExpect(jsonPath("$.records[0].claimedLevel").doesNotExist())
                .andExpect(jsonPath("$.records[0].score").doesNotExist())
                .andExpect(jsonPath("$.records[0].quote").doesNotExist())
                .andExpect(jsonPath("$.records[0].body").doesNotExist());
    }

    @Test
    void oldImportIsMarkedStaleWithoutHidingRecords() throws Exception {
        insert(0);
        repository.recordImport("governance-test-v1", "b".repeat(64), 1);
        jdbc.sql("""
                UPDATE governance_import
                   SET loaded_at = TIMESTAMP '2025-01-01 00:00:00'
                """).update();

        mvc.perform(get("/api/tracker/governance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    @Test
    void responseCapsRecordsAtFifty() throws Exception {
        for (int index = 0; index < 51; index++) {
            insert(index);
        }
        repository.recordImport("governance-test-v1", "c".repeat(64), 51);

        mvc.perform(get("/api/tracker/governance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(51))
                .andExpect(jsonPath("$.records.length()").value(50));
    }

    private void insert(int index) {
        repository.upsert(new GovernanceRecord(
                "API-" + index,
                "TREATY_STATUS",
                "INTERNATIONAL",
                "Reviewed API subject " + index,
                "PUBLISHED",
                LocalDate.of(2026, 1, 1).minusDays(index),
                "DAY",
                "UNOOSA",
                "https://www.unoosa.org/oosa/en/ourwork/spacelaw/treaties/status/index.html",
                LocalDate.of(2026, 7, 15),
                Integer.toHexString(index + 1).repeat(64).substring(0, 64),
                "PRIMARY",
                "Reviewer-authored API fact with sufficient context for testing number " + index + ".",
                "HUMAN_REVIEWED"),
                "governance-test-v1");
    }
}

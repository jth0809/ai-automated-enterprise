package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.api.TrackerAdminController;
import com.aienterprise.backend.tracker.api.TrackerAdminController.Decision;
import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.evaluate.GoldenClassifier;
import com.aienterprise.backend.tracker.evaluate.GoldenInput;
import com.aienterprise.backend.tracker.evaluate.GoldenOutput;
import com.aienterprise.backend.tracker.evaluate.GoldenSetEvaluator;
import com.aienterprise.backend.tracker.evaluate.GoldenSetJob;
import com.aienterprise.backend.tracker.ops.StateFreezeService.Trigger;
import com.aienterprise.backend.tracker.scoring.StateUpdater;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.backfill-on-boot=false",
                "tracker.golden-live-enabled=false",
                "tracker.admin-token=drill-test"
        })
@ActiveProfiles("test")
@Transactional
@Import(CircuitBreakerDrillTest.DrillConfiguration.class)
class CircuitBreakerDrillTest {

    private static final String ADMIN_TOKEN = "drill-test";
    private static final String LIVE_SUCCESS_SENTINEL = "2026-07-01T00:00:00Z";
    private static final String LIVE_BASELINE_SENTINEL = "existing-live-baseline";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private GoldenSetJob goldenSetJob;

    @Autowired
    private StateFreezeService freezeService;

    @Autowired
    private StateUpdater stateUpdater;

    @Autowired
    private TrackerAdminController controller;

    @Autowired
    private DrillGoldenClassifier classifier;

    @BeforeEach
    void resetDrillState() {
        classifier.reset();
        jdbc.sql("DELETE FROM ops_action_log").update();
        jdbc.sql("""
                DELETE FROM ops_state
                 WHERE state_key IN (
                   'STATE_FROZEN','FREEZE_REASON','FREEZE_TRIGGER','FREEZE_AT',
                   'GOLDEN_LAST_RUN_STATUS','GOLDEN_LAST_LIVE_SUCCESS',
                   'GOLDEN_LIVE_ACTIVATED','GOLDEN_BASELINE_REQUIRED')
                """).update();
    }

    @Test
    void drillProvesAutomaticFreezeHumanReleaseAndSingleReprocessing() {
        repository.putOpsState(GoldenSetJob.LAST_LIVE_SUCCESS_KEY, LIVE_SUCCESS_SENTINEL);
        repository.putOpsState(GoldenSetJob.LIVE_ACTIVATED_KEY, LIVE_BASELINE_SENTINEL);
        repository.putOpsState(GoldenSetJob.BASELINE_REQUIRED_KEY, "false");
        jdbc.sql("UPDATE capability_node SET current_level = 6 WHERE code = 'P1-REUSE-LV'")
                .update();
        long eventId = provisionalEvent();

        GoldenSetEvaluator.Report report = goldenSetJob.runForMode(
                GoldenSetEvaluator.RunMode.DRILL);

        assertEquals(50, report.totalCount());
        assertEquals(44, report.matchedCount());
        assertEquals(0.88, report.agreement());
        assertEquals(50, jdbc.sql("SELECT COUNT(*) FROM golden_set_result WHERE run_id = :runId")
                .param("runId", report.runId()).query(Integer.class).single());
        assertEquals(44, jdbc.sql("""
                SELECT COUNT(*) FROM golden_set_result
                 WHERE run_id = :runId AND matched = 'Y'
                """).param("runId", report.runId()).query(Integer.class).single());

        assertTrue(freezeService.isFrozen());
        assertEquals("DRILL", state(StateFreezeService.FREEZE_TRIGGER_KEY));
        assertTrue(state(StateFreezeService.FREEZE_REASON_KEY).contains("44/50"));
        assertEquals(LIVE_SUCCESS_SENTINEL, state(GoldenSetJob.LAST_LIVE_SUCCESS_KEY));
        assertEquals(LIVE_BASELINE_SENTINEL, state(GoldenSetJob.LIVE_ACTIVATED_KEY));
        assertEquals("false", state(GoldenSetJob.BASELINE_REQUIRED_KEY));

        stateUpdater.processEvent(repository.findEventById(eventId));
        stateUpdater.processEvent(repository.findEventById(eventId));
        long reviewId = jdbc.sql("""
                SELECT id FROM review_queue
                 WHERE event_id = :eventId AND reason = 'CIRCUIT_BREAKER'
                """).param("eventId", eventId).query(Long.class).single();
        assertEquals(6, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals("PROVISIONAL", eventField(eventId, "event_status"));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM review_queue
                 WHERE event_id = :eventId AND reason = 'CIRCUIT_BREAKER'
                """).param("eventId", eventId).query(Integer.class).single());

        var frozenApproval = controller.decide(
                reviewId, ADMIN_TOKEN, new Decision("APPROVE", "drill evidence checked"));
        assertEquals(HttpStatus.CONFLICT, frozenApproval.getStatusCode());
        assertEquals("FROZEN", frozenApproval.getBody().get("error"));
        assertEquals("PENDING", reviewStatus(reviewId));

        assertThrows(IllegalArgumentException.class,
                () -> freezeService.release("   ", Trigger.HUMAN));
        assertTrue(freezeService.release("drill root cause reviewed", Trigger.HUMAN));
        assertFalse(freezeService.isFrozen());
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM ops_action_log")
                .query(Integer.class).single());
        assertEquals("DRILL", jdbc.sql("""
                SELECT trigger_type FROM ops_action_log WHERE action_type = 'FREEZE'
                """).query(String.class).single());
        assertEquals("HUMAN", jdbc.sql("""
                SELECT trigger_type FROM ops_action_log WHERE action_type = 'RELEASE'
                """).query(String.class).single());

        stateUpdater.processPending();
        stateUpdater.processPending();

        assertEquals(7, repository.findNodeByCode("P1-REUSE-LV").currentLevel());
        assertEquals("CONFIRMED", eventField(eventId, "event_status"));
        assertEquals("Y", eventField(eventId, "state_advanced"));
        assertEquals("APPROVED", reviewStatus(reviewId));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM node_state_history WHERE cause_event_id = :eventId
                """).param("eventId", eventId).query(Integer.class).single());
    }

    private long provisionalEvent() {
        long nodeId = jdbc.sql("SELECT id FROM capability_node WHERE code = 'P1-REUSE-LV'")
                .query(Long.class).single();
        return repository.upsertEventByNaturalKey(
                "P1-REUSE-LV|OPERATIONAL_DEPLOYMENT|drill-integration|2926",
                EventRow.draft(
                        nodeId, "OPERATIONAL_DEPLOYMENT", 7, "Drill Operator",
                        LocalDate.of(2026, 7, 14), "OFFICIAL",
                        LocalDate.of(2026, 10, 12), repository.activeRubricVersionId()));
    }

    private String state(String key) {
        return repository.findOpsState(key).orElseThrow().value();
    }

    private String eventField(long eventId, String column) {
        return jdbc.sql("SELECT " + column + " FROM event WHERE id = :id")
                .param("id", eventId).query(String.class).single();
    }

    private String reviewStatus(long reviewId) {
        return jdbc.sql("SELECT status FROM review_queue WHERE id = :id")
                .param("id", reviewId).query(String.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DrillConfiguration {

        @Bean
        @Primary
        DrillGoldenClassifier drillGoldenClassifier() throws IOException {
            return new DrillGoldenClassifier();
        }
    }

    static final class DrillGoldenClassifier implements GoldenClassifier {

        private static final ObjectMapper JSON = new ObjectMapper();
        private static final int MATCHED_CASES = 44;

        private final Map<String, String> expectedByCase = new LinkedHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        DrillGoldenClassifier() throws IOException {
            try (var input = new ClassPathResource("tracker/golden-set-v1.json")
                    .getInputStream()) {
                JsonNode root = JSON.readTree(input);
                for (JsonNode item : root.path("cases")) {
                    expectedByCase.put(
                            item.path("caseCode").asText(),
                            item.path("expectedOutput").toString());
                }
            }
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public GoldenOutput classify(GoldenInput input) {
            GoldenOutput expected = GoldenOutput.fromExpectedJson(
                    expectedByCase.get(input.caseCode()));
            String quote = expected.relevant() ? input.body() : null;
            if (calls.incrementAndGet() <= MATCHED_CASES) {
                return withQuote(expected, quote);
            }
            if (expected.relevant()) {
                return new GoldenOutput(
                        true, expected.nodeCode(), expected.eventType(), expected.claimedLevel(),
                        expected.actor() + " DRILL-MISMATCH", expected.occurredOn(),
                        expected.publicationPath(), quote);
            }
            return new GoldenOutput(
                    true, "P1-REUSE-LV", "ANNOUNCEMENT_ONLY", null,
                    "Drill Operator", LocalDate.of(2026, 7, 14),
                    "PRIMARY", input.body());
        }

        private static GoldenOutput withQuote(GoldenOutput expected, String quote) {
            return new GoldenOutput(
                    expected.relevant(), expected.nodeCode(), expected.eventType(),
                    expected.claimedLevel(), expected.actor(), expected.occurredOn(),
                    expected.publicationPath(), quote);
        }
    }
}

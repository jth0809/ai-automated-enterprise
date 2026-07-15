package com.aienterprise.backend.tracker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.transport.TransportCoherenceReport;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "tracker.enabled=true",
                "tracker.admin-token=test-secret"})
@ActiveProfiles("test")
@Transactional
class TransportCoherenceAdminControllerTest {

    private static final String TOKEN_HEADER = "X-Tracker-Admin-Token";
    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 30);

    @Autowired
    private TransportCoherenceAdminController controller;

    @Autowired
    private TransportEconomicsRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void missingOrWrongTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/tracker/admin/coherence/transport"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tracker/admin/coherence/transport")
                        .header(TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportEndpointReturnsOnlyReportAndSampleState() throws Exception {
        long sampleId = createSample();

        mvc.perform(get("/api/tracker/admin/coherence/transport")
                        .header(TOKEN_HEADER, "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.state").value("DIVERGENT"))
                .andExpect(jsonPath("$.report.alertActive").value(true))
                .andExpect(jsonPath("$.samples[0].id").value(sampleId))
                .andExpect(jsonPath("$.samples[0].status").value("PENDING"))
                .andExpect(jsonPath("$.samples[0].event").doesNotExist())
                .andExpect(jsonPath("$.samples[0].eventBody").doesNotExist());
    }

    @Test
    void blankOrOversizedReviewNoteIsRejected() throws Exception {
        long sampleId = createSample();

        mvc.perform(post("/api/tracker/admin/coherence/transport/samples/{id}", sampleId)
                        .header(TOKEN_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tracker/admin/coherence/transport/samples/{id}", sampleId)
                        .header(TOKEN_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "a".repeat(2_001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSampleIsNotFound() throws Exception {
        saveReport();

        mvc.perform(post("/api/tracker/admin/coherence/transport/samples/999999")
                        .header(TOKEN_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"checked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewIsTrimmedOneWayAndDoesNotMutateReferencedEvent() throws Exception {
        long sampleId = createSample();
        long eventId = repository.findSamples(
                repository.findLatestCoherenceReport().orElseThrow().id())
                .get(0).eventId();
        String before = eventFingerprint(eventId);

        mvc.perform(post("/api/tracker/admin/coherence/transport/samples/{id}", sampleId)
                        .header(TOKEN_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"  independently checked  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sampleId))
                .andExpect(jsonPath("$.status").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewerNote").value("independently checked"))
                .andExpect(jsonPath("$.event").doesNotExist())
                .andExpect(jsonPath("$.eventBody").doesNotExist());

        assertEquals(before, eventFingerprint(eventId));

        mvc.perform(post("/api/tracker/admin/coherence/transport/samples/{id}", sampleId)
                        .header(TOKEN_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"second attempt\"}"))
                .andExpect(status().isConflict());
    }

    private long createSample() {
        long reportId = saveReport();
        long eventId = insertConfirmedPillarOneEvent();
        return repository.insertSample(reportId, eventId);
    }

    private long saveReport() {
        return repository.saveCoherenceReport(new TransportCoherenceReport(
                0, PERIOD, PERIOD,
                "ADVANCING", "ADVANCING", "ADVANCING", "FLAT",
                "DIVERGENT", "B_AHEAD", 2, true,
                new BigDecimal("1.50"), PERIOD));
    }

    private long insertConfirmedPillarOneEvent() {
        Long nodeId = jdbc.sql("""
                SELECT id FROM capability_node WHERE pillar = 1
                ORDER BY id FETCH FIRST 1 ROWS ONLY
                """).query(Long.class).single();
        Long rubricId = jdbc.sql("""
                SELECT id FROM rubric_version ORDER BY id DESC FETCH FIRST 1 ROWS ONLY
                """).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO event
                  (natural_key, node_id, event_type, claimed_level, actor,
                   occurred_on, verification_level, event_status, impact_score,
                   novelty, state_advanced, rubric_version_id)
                VALUES (:key, :nodeId, 'FLIGHT_TEST', 5, 'Test operator',
                        :occurredOn, 'OFFICIAL', 'CONFIRMED', 0.75,
                        1, 'N', :rubricId)
                """)
                .param("key", "wp33-admin-test-" + System.nanoTime())
                .param("nodeId", nodeId)
                .param("occurredOn", java.sql.Date.valueOf(LocalDate.of(2026, 5, 1)))
                .param("rubricId", rubricId)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM event")
                .query(Long.class)
                .single();
    }

    private String eventFingerprint(long eventId) {
        return jdbc.sql("""
                SELECT natural_key || '|' || event_status || '|' || state_advanced
                       || '|' || COALESCE(CAST(impact_score AS VARCHAR), '')
                  FROM event WHERE id = :id
                """)
                .param("id", eventId)
                .query(String.class)
                .single();
    }
}

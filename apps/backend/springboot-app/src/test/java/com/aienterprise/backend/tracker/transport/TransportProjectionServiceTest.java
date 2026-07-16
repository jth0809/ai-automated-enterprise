package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.ingest.TransportEconomicsLoader;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TransportProjectionServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 15);
    private static final BigDecimal CENTRAL = new BigDecimal("200");
    private static final BigDecimal EASY = new BigDecimal("500");
    private static final BigDecimal HARD = new BigDecimal("100");

    @Autowired
    private TransportProjectionService service;

    @Autowired
    private TransportEconomicsRepository repository;

    @Autowired
    private TransportEconomicsLoader loader;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void matchingRuntimeTargetsProjectReviewedCorpusAndPersistResult() {
        loader.loadIfNeeded();

        TransportProjection result = service.run(AS_OF, CENTRAL, EASY, HARD);

        assertEquals("PROVISIONAL", result.status());
        assertEquals(3, result.observationCount());
        assertNotNull(result.centralEtaYear());
        assertEquals("PUBLISHED_PRICE", result.basis());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM transport_economics_projection")
                .query(Integer.class).single());
        assertEquals(result.status(),
                repository.findLatestProjection().orElseThrow().status());
    }

    @Test
    void runtimeTargetMismatchFailsBeforeReplacingLatestProjection() {
        loader.loadIfNeeded();
        repository.saveProjection(
                TransportTestFixtures.projection(LocalDate.of(2026, 7, 14)));
        TransportProjection before = repository.findLatestProjection().orElseThrow();

        assertThrows(IllegalStateException.class,
                () -> service.run(AS_OF, new BigDecimal("201"), EASY, HARD));

        assertEquals(before, repository.findLatestProjection().orElseThrow());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM transport_economics_projection")
                .query(Integer.class).single());
    }
}

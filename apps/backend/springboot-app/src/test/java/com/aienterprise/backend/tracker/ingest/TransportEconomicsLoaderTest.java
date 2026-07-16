package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.aienterprise.backend.tracker.transport.TransportEconomicsDatasetValidatorTest;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TransportEconomicsLoaderTest {

    @Autowired
    private TransportEconomicsRepository transportRepository;

    @Autowired
    private TrackerRepository trackerRepository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void importsValidatedInputsAndMirrorsAnnualCountsAndPriceFrontiersIdempotently() {
        TransportEconomicsLoader loader = loader(
                TransportEconomicsDatasetValidatorTest.validJson(),
                "transport-economics-v1");

        loader.loadIfNeeded();
        loader.loadIfNeeded();

        assertEquals(3, transportRepository.findPriceObservations().size());
        assertEquals(9, transportRepository.findAnnualFalconLaunchCounts().size());
        assertEquals(12, trackerRepository.countLayerBMetrics());
        assertEquals(3, countLayerB("LEO_PUBLISHED_PRICE_FRONTIER_REAL_2025"));
        assertEquals(9, countLayerB("ANNUAL_FALCON_FAMILY_LAUNCH_COUNT"));
        assertEquals(0, jdbc.sql("""
                SELECT metric_value FROM layer_b_metric
                 WHERE metric_code = 'ANNUAL_FALCON_FAMILY_LAUNCH_COUNT'
                   AND observed_on = DATE '2011-12-31'
                """).query(Integer.class).single());
    }

    @Test
    void changedHashForTheSameVersionFailsBeforeAnyDomainWrite() {
        transportRepository.recordImport(
                "transport-economics-v1", "c".repeat(64), 3, 9, 4);

        assertThrows(IllegalStateException.class, () -> loader(
                TransportEconomicsDatasetValidatorTest.validJson(),
                "transport-economics-v1").loadIfNeeded());

        assertEquals(0, transportRepository.findPriceObservations().size());
        assertEquals(0, trackerRepository.countLayerBMetrics());
    }

    @Test
    void invalidDatasetOrDeclaredVersionMismatchWritesNothing() {
        String valid = TransportEconomicsDatasetValidatorTest.validJson();
        String invalid = valid.replaceFirst("\\{", "{\"extra\":true,");
        assertThrows(IllegalStateException.class,
                () -> loader(invalid, "transport-economics-v1").loadIfNeeded());
        assertThrows(IllegalStateException.class,
                () -> loader(valid, "transport-economics-v2").loadIfNeeded());

        assertEquals(0, transportRepository.findPriceObservations().size());
        assertEquals(0, trackerRepository.countLayerBMetrics());
    }

    @Test
    void conflictingLayerBMirrorFailsBeforeAnyTransportDomainWrite() {
        trackerRepository.upsertLayerBMetric(new LayerBMetric(
                0, "ANNUAL_FALCON_FAMILY_LAUNCH_COUNT", 1,
                LocalDate.of(2010, 12, 31), new BigDecimal("999"),
                "LAUNCHES", "MEASURED", "Conflicting source",
                "https://example.test/conflict", LocalDate.of(2026, 7, 15),
                "d".repeat(64), "Conflicting pre-existing value."));

        TransportEconomicsLoader loader = loader(
                TransportEconomicsDatasetValidatorTest.validJson(),
                "transport-economics-v1");

        assertThrows(IllegalStateException.class, loader::loadIfNeeded);
        assertEquals(0, transportRepository.findPriceObservations().size());
        assertEquals(1, trackerRepository.countLayerBMetrics());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM transport_economics_import")
                .query(Integer.class).single());
    }

    private TransportEconomicsLoader loader(String json, String version) {
        return new TransportEconomicsLoader(
                transportRepository, trackerRepository,
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)), version);
    }

    private int countLayerB(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM layer_b_metric WHERE metric_code = :code")
                .param("code", code)
                .query(Integer.class)
                .single();
    }
}

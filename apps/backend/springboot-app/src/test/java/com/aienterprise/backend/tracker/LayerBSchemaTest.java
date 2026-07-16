package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    private void insert(String code, int pillar, String basis) {
        jdbc.sql("""
                INSERT INTO layer_b_metric
                  (metric_code, pillar, observed_on, metric_value, unit, basis,
                   source_label, source_url, accessed_on, content_sha256, fact_summary)
                VALUES
                  (:code, :pillar, DATE '2026-01-01', 2600, 'USD_PER_KG', :basis,
                   'Provider price sheet', 'https://example.test/price', DATE '2026-07-14',
                   :hash, 'Published price per kilogram to LEO.')
                """)
                .param("code", code).param("pillar", pillar).param("basis", basis)
                .param("hash", "a".repeat(64)).update();
    }

    @Test
    void acceptsValidMetricAndRejectsBadBasisAndPillar() {
        insert("LAUNCH_PRICE_LEO", 1, "PUBLISHED_PRICE");
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("BAD_BASIS", 1, "GUESS"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("BAD_PILLAR", 9, "MEASURED"));
    }

    @Test
    void importAuditEnforcesPositiveCountAndHashUniqueness() {
        jdbc.sql("INSERT INTO layer_b_metric_import (dataset_version, dataset_sha256, record_count) VALUES ('lb-v1', :h, 3)")
                .param("h", "b".repeat(64)).update();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.sql("INSERT INTO layer_b_metric_import (dataset_version, dataset_sha256, record_count) VALUES ('lb-empty', :h, 0)")
                        .param("h", "c".repeat(64)).update());
    }
}

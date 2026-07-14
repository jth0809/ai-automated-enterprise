package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.TrackerRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBLoaderTest {

    @Autowired
    private TrackerRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void loadsSeedOnceAndIsIdempotent() {
        LayerBLoader loader = new LayerBLoader(repository,
                new ClassPathResource("tracker/layer-b-metrics-v1.json"), "layer-b-v1");

        loader.loadIfNeeded();
        int afterFirst = repository.countLayerBMetrics();
        loader.loadIfNeeded();

        assertEquals(afterFirst, repository.countLayerBMetrics());
        assertEquals(3, afterFirst);
        assertEquals(1, jdbc.sql(
                "SELECT COUNT(*) FROM layer_b_metric_import WHERE dataset_version = 'layer-b-v1'")
                .query(Integer.class).single());
    }
}

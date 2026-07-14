package com.aienterprise.backend.tracker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class LayerBMetricRepositoryTest {

    @Autowired
    private TrackerRepository repository;

    private LayerBMetric draft(String code, LocalDate on, String value) {
        return new LayerBMetric(0, code, 1, on, new BigDecimal(value), "USD_PER_KG",
                "PUBLISHED_PRICE", "Provider price sheet", "https://example.test/p",
                LocalDate.of(2026, 7, 14), "a".repeat(64), "Published price per kg to LEO.");
    }

    @Test
    void upsertIsIdempotentByNaturalKey() {
        long first = repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2026, 1, 1), "2600"));
        long second = repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2026, 1, 1), "2600"));
        assertEquals(first, second);
        assertEquals(1, repository.countLayerBMetrics());
    }

    @Test
    void findLatestByPillarReturnsMostRecentPerCode() {
        repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2024, 1, 1), "6000"));
        repository.upsertLayerBMetric(draft("LAUNCH_PRICE_LEO", LocalDate.of(2026, 1, 1), "2600"));
        var latest = repository.findLatestLayerBByPillar().stream()
                .filter(m -> m.metricCode().equals("LAUNCH_PRICE_LEO")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("2600").compareTo(latest.value()));
    }
}

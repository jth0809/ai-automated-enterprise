package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TrackerHistoricalSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HISTORICAL_ONLY = Set.of("FAA", "UNOOSA", "GOVINFO", "LSA");
    private static final Set<String> CATALOG_CODES =
            Set.of("NASA", "ESA", "FAA", "UNOOSA", "GOVINFO", "LSA");

    @Autowired
    private JdbcClient jdbc;

    @Test
    void sourceRegistryMatchesApprovedHistoricalCatalog() throws IOException {
        Map<String, CatalogSource> catalog = readCatalog();
        Map<String, CatalogSource> database = new HashMap<>();
        jdbc.sql("""
                SELECT code, source_type, tier, site_domain, feed_active
                  FROM source_registry
                 WHERE code IN ('NASA','ESA','FAA','UNOOSA','GOVINFO','LSA')
                """)
                .query((rs, rowNum) -> new CatalogSource(
                        rs.getString("code"),
                        rs.getString("source_type"),
                        rs.getInt("tier"),
                        rs.getString("site_domain"),
                        "Y".equals(rs.getString("feed_active"))))
                .list()
                .forEach(source -> database.put(source.code(), source));

        assertEquals(catalog.keySet(), database.keySet());
        for (CatalogSource expected : catalog.values()) {
            CatalogSource actual = database.get(expected.code());
            assertEquals(expected.sourceType(), actual.sourceType(), expected.code());
            assertEquals(expected.tier(), actual.tier(), expected.code());
            assertTrue(actual.domain().equals(expected.domain())
                    || actual.domain().endsWith("." + expected.domain()), expected.code());
            assertEquals(expected.feedActive(), actual.feedActive(), expected.code());
        }
    }

    @Test
    void historicalOnlySourcesActivateNoFeedOrEgressPolicy() {
        Integer activeFeeds = jdbc.sql("""
                SELECT COUNT(*) FROM source_registry
                 WHERE code IN ('FAA','UNOOSA','GOVINFO','LSA')
                   AND (feed_active <> 'N' OR rss_url IS NOT NULL)
                """).query(Integer.class).single();
        assertEquals(0, activeFeeds);

        Integer domains = jdbc.sql("""
                SELECT COUNT(*)
                  FROM source_domain d
                  JOIN source_registry s ON s.id = d.source_id
                 WHERE s.code IN ('FAA','UNOOSA','GOVINFO','LSA')
                """).query(Integer.class).single();
        assertEquals(0, domains);
    }

    private static Map<String, CatalogSource> readCatalog() throws IOException {
        JsonNode root;
        try (var input = new ClassPathResource(
                "tracker/historical-source-catalog-v1.json").getInputStream()) {
            root = JSON.readTree(input);
        }
        Map<String, CatalogSource> result = new HashMap<>();
        for (JsonNode source : root) {
            String code = source.path("sourceCode").asText();
            result.put(code, new CatalogSource(
                    code,
                    source.path("sourceType").asText(),
                    source.path("tier").asInt(),
                    source.path("domain").asText(),
                    source.path("feedActive").asBoolean()));
        }
        assertEquals(CATALOG_CODES, result.keySet());
        assertTrue(result.keySet().containsAll(HISTORICAL_ONLY));
        return result;
    }

    private record CatalogSource(
            String code,
            String sourceType,
            int tier,
            String domain,
            boolean feedActive) {
    }
}

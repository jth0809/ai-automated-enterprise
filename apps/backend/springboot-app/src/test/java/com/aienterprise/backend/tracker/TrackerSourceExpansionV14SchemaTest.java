package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "tracker.enabled=true")
@ActiveProfiles("test")
@Transactional
class TrackerSourceExpansionV14SchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsEvaluationQuarantineAndGovernanceLedgers() {
        Set<String> articleColumns = Set.copyOf(jdbc.sql("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = 'ARTICLE'
                """).query(String.class).list());

        assertTrue(articleColumns.contains("EVALUATION_ALLOWED"));
        assertEquals(0, count("governance_record"));
        assertEquals(0, count("governance_import"));
    }

    @Test
    void registersOfficialAndHostedMediaSourcesWithoutPretendingTheyAreRssFeeds() {
        assertSource("ISRO", "AGENCY", 1, "N");
        assertSource("CNSA", "AGENCY", 1, "N");
        assertSource("CNSA_HOSTED_MEDIA", "GENERAL_MEDIA", 3, "N");

        assertDomain("ISRO", "www.isro.gov.in");
        assertDomain("CNSA", "www.cnsa.gov.cn");
        assertDomain("CNSA_HOSTED_MEDIA", "www.cnsa.gov.cn");
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table)
                .query(Integer.class).single();
    }

    private void assertSource(String code, String sourceType, int tier, String feedActive) {
        var row = jdbc.sql("""
                SELECT source_type, tier, feed_active
                  FROM source_registry
                 WHERE code = :code
                """).param("code", code)
                .query((rs, rowNum) -> new SourceRow(
                        rs.getString("source_type"),
                        rs.getInt("tier"),
                        rs.getString("feed_active")))
                .single();

        assertEquals(new SourceRow(sourceType, tier, feedActive), row);
    }

    private void assertDomain(String code, String domain) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                  FROM source_domain d
                  JOIN source_registry s ON s.id = d.source_id
                 WHERE s.code = :code
                   AND d.domain = :domain
                   AND d.purpose = 'BOTH'
                   AND d.active = 'Y'
                """).param("code", code).param("domain", domain)
                .query(Integer.class).single();
        assertEquals(1, count);
    }

    private record SourceRow(String sourceType, int tier, String feedActive) {
    }
}

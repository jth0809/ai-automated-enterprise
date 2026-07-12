package com.aienterprise.backend.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TrackerPhase1bSchemaTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void phase1bBodyExtractionSchemaExists() {
        assertColumn("ARTICLE", "BODY_EXTRACTION_STATUS");
        assertColumn("ARTICLE", "BODY_EXTRACTION_ATTEMPTS");
        assertColumn("ARTICLE", "BODY_EXTRACTION_ERROR");

        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                  FROM INFORMATION_SCHEMA.TABLES
                 WHERE TABLE_NAME = 'SOURCE_DOMAIN'
                """)
                .query(Integer.class)
                .single();
        assertEquals(1, count);
    }

    @Test
    void rssSummaryArticlesDefaultToSkippedExtraction() {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO article
                  (source_id, url, url_hash, title, body, body_extracted, pipeline_status)
                VALUES
                  (:sourceId, 'https://example.test/legacy', :hash, 'Legacy', 'RSS summary', 'N', 'INGESTED')
                """)
                .param("sourceId", sourceId)
                .param("hash", "f".repeat(64))
                .update();

        String status = jdbc.sql("SELECT body_extraction_status FROM article WHERE url_hash = :hash")
                .param("hash", "f".repeat(64))
                .query(String.class)
                .single();

        assertEquals("SKIPPED", status);
    }

    @Test
    void oneSourceCannotDeclareTheSameDomainTwice() {
        long sourceId = jdbc.sql("SELECT id FROM source_registry WHERE code = 'NASA'")
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO source_domain (source_id, domain, purpose, active)
                VALUES (:sourceId, 'example.test', 'BODY', 'Y')
                """)
                .param("sourceId", sourceId)
                .update();

        assertThrows(DuplicateKeyException.class, () -> jdbc.sql("""
                INSERT INTO source_domain (source_id, domain, purpose, active)
                VALUES (:sourceId, 'example.test', 'FEED', 'Y')
                """)
                .param("sourceId", sourceId)
                .update());
    }

    private void assertColumn(String table, String column) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                  FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE TABLE_NAME = :tableName
                   AND COLUMN_NAME = :columnName
                """)
                .param("tableName", table)
                .param("columnName", column)
                .query(Integer.class)
                .single();
        assertTrue(count == 1, () -> table + "." + column + " is missing");
    }
}

package com.aienterprise.backend.api;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain unit test (no Spring context): CI runs without a database, so the
 * contract under test is graceful degradation, not connectivity.
 */
class StatusControllerTest {

    @Test
    void reportsServiceUpAndDatabaseDownWhenDbUnreachable() {
        JdbcTemplate noDb = new JdbcTemplate(); // no DataSource -> query throws
        Map<String, Object> body = new StatusController(noDb).status();

        assertEquals("UP", body.get("status"));
        assertEquals("DOWN", body.get("database"));
        assertEquals("backend-springboot", body.get("service"));
    }
}

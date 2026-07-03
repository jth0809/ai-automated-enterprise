package com.aienterprise.backend.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final JdbcTemplate jdbcTemplate;

    public StatusController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        // Degrade gracefully: a DB outage must not take the API down with it.
        String dbStatus;
        try {
            jdbcTemplate.queryForObject("SELECT 1 FROM DUAL", Integer.class);
            dbStatus = "UP";
        } catch (Exception e) {
            dbStatus = "DOWN";
        }
        return Map.of(
                "service", "backend-springboot",
                "status", "UP",
                "database", dbStatus,
                "timestamp", Instant.now().toString()
        );
    }
}

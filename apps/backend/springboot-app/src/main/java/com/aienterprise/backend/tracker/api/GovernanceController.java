package com.aienterprise.backend.tracker.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.governance.GovernanceRecord;
import com.aienterprise.backend.tracker.governance.GovernanceRepository;

/** Public reviewed governance references; deliberately carries no score or ETA. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker")
public class GovernanceController {

    private static final int MAX_RECORDS = 50;
    private static final Duration STALE_AFTER = Duration.ofDays(400);

    private final GovernanceRepository repository;
    private final Clock clock;

    @Autowired
    public GovernanceController(GovernanceRepository repository) {
        this(repository, Clock.systemUTC());
    }

    GovernanceController(GovernanceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/governance")
    public ResponseEntity<Map<String, Object>> summary() {
        var latestImport = repository.latestImport();
        List<GovernanceRecord> records = repository.findAll(MAX_RECORDS);
        if (latestImport.isEmpty() || records.isEmpty()) {
            return ResponseEntity.ok(emptyBody());
        }

        Instant staleBoundary = Instant.now(clock).minus(STALE_AFTER);
        String status = latestImport.get().loadedAt().isBefore(staleBoundary)
                ? "STALE" : "CURRENT";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("datasetVersion", latestImport.get().datasetVersion());
        body.put("recordCount", latestImport.get().recordCount());
        body.put("latestEffectiveOn", records.getFirst().effectiveOn().toString());
        body.put("records", records(records));
        return ResponseEntity.ok(body);
    }

    private static List<Map<String, Object>> records(List<GovernanceRecord> records) {
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (GovernanceRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recordId", record.recordId());
            item.put("recordType", record.recordType());
            item.put("jurisdiction", record.jurisdiction());
            item.put("subject", record.subject());
            item.put("status", record.status());
            item.put("effectiveOn", record.effectiveOn().toString());
            item.put("effectiveOnPrecision", record.effectiveOnPrecision());
            item.put("sourceCode", record.sourceCode());
            item.put("sourceUrl", record.sourceUrl());
            item.put("accessedOn", record.accessedOn().toString());
            item.put("contentSha256", record.contentSha256());
            item.put("publicationPath", record.publicationPath());
            item.put("factSummary", record.factSummary());
            item.put("reviewStatus", record.reviewStatus());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> emptyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "INSUFFICIENT_DATA");
        body.put("datasetVersion", null);
        body.put("recordCount", 0);
        body.put("latestEffectiveOn", null);
        body.put("records", List.of());
        return body;
    }
}

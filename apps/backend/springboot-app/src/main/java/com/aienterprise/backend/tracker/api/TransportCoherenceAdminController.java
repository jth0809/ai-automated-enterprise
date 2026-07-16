package com.aienterprise.backend.tracker.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.transport.TransportCoherenceReport;
import com.aienterprise.backend.tracker.transport.TransportCoherenceSample;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;

/** Operations-only review surface for bounded, read-only coherence samples. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker/admin/coherence/transport")
public class TransportCoherenceAdminController {

    public record ReviewRequest(String note) {
    }

    private static final int MAX_NOTE_LENGTH = 2_000;

    private final TransportEconomicsRepository repository;
    private final String adminToken;

    public TransportCoherenceAdminController(
            TransportEconomicsRepository repository,
            @Value("${tracker.admin-token:}") String adminToken) {
        this.repository = repository;
        this.adminToken = adminToken;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> latest(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
                    String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<TransportCoherenceReport> report = repository.findLatestCoherenceReport();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report", report
                .map(TransportEconomicsController::coherenceBody).orElse(null));
        body.put("samples", report
                .map(value -> repository.findSamples(value.id()).stream()
                        .map(TransportCoherenceAdminController::sampleBody)
                        .toList())
                .orElseGet(List::of));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/samples/{id}")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false)
                    String token,
            @RequestBody(required = false) ReviewRequest request) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String note = request == null || request.note() == null
                ? "" : request.note().trim();
        if (note.isEmpty() || note.length() > MAX_NOTE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "note must contain 1..2000 characters"));
        }

        Optional<TransportCoherenceReport> latest = repository.findLatestCoherenceReport();
        if (latest.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<TransportCoherenceSample> sample = repository
                .findSamples(latest.get().id()).stream()
                .filter(value -> value.id() == id)
                .findFirst();
        if (sample.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!"PENDING".equals(sample.get().status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "sample already reviewed"));
        }
        if (!repository.reviewSample(id, note)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "sample state changed"));
        }
        TransportCoherenceSample reviewed = repository
                .findSamples(latest.get().id()).stream()
                .filter(value -> value.id() == id)
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(sampleBody(reviewed));
    }

    private static Map<String, Object> sampleBody(TransportCoherenceSample sample) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", sample.id());
        body.put("reportId", sample.reportId());
        body.put("eventId", sample.eventId());
        body.put("status", sample.status());
        body.put("reviewerNote", sample.reviewerNote());
        body.put("reviewedAt", sample.reviewedAt() == null
                ? null : sample.reviewedAt().toString());
        return body;
    }

    private boolean authorized(String token) {
        if (adminToken.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}

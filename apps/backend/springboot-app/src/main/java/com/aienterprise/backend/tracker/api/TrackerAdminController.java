package com.aienterprise.backend.tracker.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.domain.ReviewCase;
import com.aienterprise.backend.tracker.domain.ReviewPage;
import com.aienterprise.backend.tracker.domain.ReviewPage.Reason;
import com.aienterprise.backend.tracker.domain.ReviewPage.Status;
import com.aienterprise.backend.tracker.domain.ReviewRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.scoring.StateUpdater;
import com.aienterprise.backend.tracker.scoring.StateUpdater.DecisionOutcome;

@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker/admin")
public class TrackerAdminController {

    public record Decision(String decision, String note) {
    }

    private final TrackerRepository repository;
    private final StateUpdater stateUpdater;
    private final String adminToken;

    public TrackerAdminController(
            TrackerRepository repository,
            StateUpdater stateUpdater,
            @Value("${tracker.admin-token:}") String adminToken) {
        this.repository = repository;
        this.stateUpdater = stateUpdater;
        this.adminToken = adminToken;
    }

    @GetMapping("/review")
    public ResponseEntity<List<ReviewCase>> reviewQueue(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(repository.findPendingReviewCases(100));
    }

    @GetMapping("/reviews")
    public ResponseEntity<ReviewPage> reviewPage(
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false) String token,
            @RequestParam(value = "status", defaultValue = "PENDING") String status,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (page < 0 || size < 1 || size > 100) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Status parsedStatus = Status.valueOf(normalized(status));
            Reason parsedReason = reason == null || reason.isBlank()
                    ? null
                    : Reason.valueOf(normalized(reason));
            return ResponseEntity.ok(repository.findReviewPage(
                    parsedStatus, parsedReason, page, size));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/review/{id}")
    public ResponseEntity<Map<String, Object>> decide(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false) String token,
            @RequestBody Decision body) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String decision = body.decision() == null ? "" : body.decision().trim().toUpperCase();
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision must be APPROVE or REJECT"));
        }
        String note = body.note() == null ? null : body.note().trim();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if ("REJECT".equals(decision) && note == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "rejection requires a note"));
        }
        if (note != null && note.length() > 2_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "note must be at most 2000 characters"));
        }
        Optional<ReviewRow> review = repository.findReviewById(id);
        if (review.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!"PENDING".equals(review.get().status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "review already resolved", "status", review.get().status()));
        }
        if ("APPROVE".equals(decision)) {
            DecisionOutcome outcome = stateUpdater.approve(review.get(), note);
            if (outcome == DecisionOutcome.FROZEN) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "FROZEN"));
            }
            if (outcome == DecisionOutcome.ALREADY_RESOLVED) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "review already resolved"));
            }
        } else {
            stateUpdater.reject(review.get(), note);
        }
        return ResponseEntity.ok(Map.of("id", id, "status", "APPROVE".equals(decision) ? "APPROVED" : "REJECTED"));
    }

    @PostMapping("/reviews/{id}/decision")
    public ResponseEntity<Map<String, Object>> decideFormal(
            @PathVariable("id") long id,
            @RequestHeader(value = "X-Tracker-Admin-Token", required = false) String token,
            @RequestBody Decision body) {
        return decide(id, token, body);
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("blank enum value");
        }
        return value.trim().toUpperCase(Locale.ROOT);
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

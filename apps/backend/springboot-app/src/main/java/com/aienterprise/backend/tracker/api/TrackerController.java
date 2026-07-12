package com.aienterprise.backend.tracker.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TimelineRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker")
public class TrackerController {

    // v2.10 honesty label: fixed wording, served verbatim with every summary.
    static final String LABEL = "현 추세 지속 시나리오 기준 · 모델 내 80% 구간";

    private static final Map<Integer, String> PILLAR_NAMES = Map.of(
            1, "수송",
            2, "생명 유지",
            3, "거주 인프라",
            4, "자원·에너지",
            5, "로봇·자율 운영",
            6, "경제·거버넌스");

    private final TrackerRepository repository;

    public TrackerController(TrackerRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Optional<SnapshotRow> overall = repository.findLatestSnapshot(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayedEtaYear", overall.map(SnapshotRow::displayedEtaYear).orElse(null));
        body.put("etaLow", overall.map(SnapshotRow::etaLow).orElse(null));
        body.put("etaHigh", overall.map(SnapshotRow::etaHigh).orElse(null));
        body.put("label", LABEL);
        body.put("overallReadiness", overall.map(SnapshotRow::readiness).orElse(null));
        body.put("bottleneckPillar", bottleneckPillar());
        body.put("frozen", repository.findOpsState("STATE_FROZEN")
                .map(state -> "Y".equals(state.value())).orElse(false));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/pillars")
    public ResponseEntity<List<Map<String, Object>>> pillars() {
        List<Map<String, Object>> body = new ArrayList<>();
        for (int pillar = 1; pillar <= 6; pillar++) {
            Optional<SnapshotRow> latest = repository.findLatestSnapshot(pillar);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pillar", pillar);
            entry.put("name", PILLAR_NAMES.get(pillar));
            entry.put("readiness", latest.map(SnapshotRow::readiness).orElse(null));
            entry.put("etaYear", latest.map(SnapshotRow::etaYear).orElse(null));
            entry.put("momentum", latest.map(SnapshotRow::trendUsed).orElse(null));
            body.add(entry);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> events(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (TimelineRow row : repository.findEventTimeline(limit)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("occurredOn", row.occurredOn());
            entry.put("nodeName", row.nodeName());
            entry.put("eventType", row.eventType());
            entry.put("levelFrom", row.levelFrom());
            entry.put("levelTo", row.levelTo());
            entry.put("impactScore", row.impactScore());
            entry.put("verificationLevel", row.verificationLevel());
            entry.put("sourceCount", row.sourceCount());
            entry.put("evidenceQuote", row.evidenceQuote());
            body.add(entry);
        }
        return ResponseEntity.ok(body);
    }

    private Integer bottleneckPillar() {
        Integer bottleneck = null;
        double worst = Double.MAX_VALUE;
        for (int pillar = 1; pillar <= 6; pillar++) {
            Optional<SnapshotRow> latest = repository.findLatestSnapshot(pillar);
            if (latest.isPresent() && latest.get().readiness() < worst) {
                worst = latest.get().readiness();
                bottleneck = pillar;
            }
        }
        return bottleneck;
    }
}

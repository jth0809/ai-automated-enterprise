package com.aienterprise.backend.tracker.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
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

import com.aienterprise.backend.tracker.kindex.KIndexCalculator;
import com.aienterprise.backend.tracker.kindex.KIndexObservation;
import com.aienterprise.backend.tracker.kindex.KIndexRepository;

/** Public annual K-index gauge; it intentionally exposes no ETA semantics. */
@RestController
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
@RequestMapping("/api/tracker")
public class KIndexController {

    private static final int MAX_SERIES_POINTS = 80;

    private final KIndexRepository repository;
    private final Clock clock;

    @Autowired
    public KIndexController(KIndexRepository repository) {
        this(repository, Clock.systemUTC());
    }

    KIndexController(KIndexRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/k-index")
    public ResponseEntity<Map<String, Object>> summary() {
        List<KIndexObservation> observations = repository.findAll();
        if (observations.isEmpty()) {
            return ResponseEntity.ok(emptyBody());
        }

        KIndexObservation latest = observations.getLast();
        KIndexCalculator.Calculation calculation = new KIndexCalculator()
                .calculate(latest.primaryEnergyTwh());
        BigDecimal annualDelta = observations.size() < 2 ? null
                : latest.kValue().subtract(
                        observations.get(observations.size() - 2).kValue())
                        .setScale(4, RoundingMode.HALF_UP);
        int currentYear = LocalDate.now(clock).getYear();
        String status = latest.year() >= currentYear - 2 ? "CURRENT" : "STALE";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("latestYear", latest.year());
        body.put("primaryEnergyTwh", latest.primaryEnergyTwh());
        body.put("powerWatts", latest.powerWatts());
        body.put("kValue", latest.kValue());
        body.put("annualDelta", annualDelta);
        body.put("typeOneGap", calculation.typeOneGap());
        body.put("typeOneMultiplier", calculation.typeOneMultiplier());
        body.put("accountingBasis", latest.accountingBasis());
        body.put("sourceName", latest.sourceName());
        body.put("sourceUrl", latest.sourceUrl());
        body.put("accessedOn", latest.accessedOn().toString());
        body.put("series", series(observations));
        return ResponseEntity.ok(body);
    }

    private static List<Map<String, Object>> series(
            List<KIndexObservation> observations) {
        int start = Math.max(0, observations.size() - MAX_SERIES_POINTS);
        List<Map<String, Object>> result = new ArrayList<>(
                observations.size() - start);
        for (int index = start; index < observations.size(); index++) {
            KIndexObservation observation = observations.get(index);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("year", observation.year());
            point.put("kValue", observation.kValue());
            result.add(point);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> emptyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "INSUFFICIENT_DATA");
        body.put("latestYear", null);
        body.put("primaryEnergyTwh", null);
        body.put("powerWatts", null);
        body.put("kValue", null);
        body.put("annualDelta", null);
        body.put("typeOneGap", null);
        body.put("typeOneMultiplier", null);
        body.put("accountingBasis", null);
        body.put("sourceName", null);
        body.put("sourceUrl", null);
        body.put("accessedOn", null);
        body.put("series", List.of());
        return body;
    }
}

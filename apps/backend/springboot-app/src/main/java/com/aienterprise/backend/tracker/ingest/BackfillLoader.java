package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aienterprise.backend.tracker.domain.EventRow;
import com.aienterprise.backend.tracker.domain.NodeRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.event.EventMerger;
import com.aienterprise.backend.tracker.math.LogitEta;
import com.aienterprise.backend.tracker.math.Params;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class BackfillLoader {

    record BackfillItem(
            String nodeCode,
            String eventType,
            Integer claimedLevel,
            String actor,
            LocalDate occurredOn,
            String verificationLevel,
            String title) {
    }

    private static final Logger log = LoggerFactory.getLogger(BackfillLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FIRST_SNAPSHOT_YEAR = 1960;

    private final TrackerRepository repository;
    private final String resourcePath;

    public BackfillLoader(
            TrackerRepository repository,
            @Value("${tracker.backfill-resource:tracker/backfill-v0.json}") String resourcePath) {
        this.repository = repository;
        this.resourcePath = resourcePath;
    }

    @Transactional
    public void loadIfEmpty() {
        if (repository.countEvents() > 0) {
            return;
        }
        List<BackfillItem> items = readItems();
        if (items.isEmpty()) {
            log.info("tracker backfill resource {} is empty; skipping replay", resourcePath);
            return;
        }
        items.sort(Comparator.comparing(BackfillItem::occurredOn));
        long rubricVersionId = repository.activeRubricVersionId();
        replay(items, rubricVersionId);
        snapshotYearEnds(items);
        log.info("tracker backfill replayed {} events from {}", items.size(), resourcePath);
    }

    private void replay(List<BackfillItem> items, long rubricVersionId) {
        for (BackfillItem item : items) {
            NodeRow node = repository.findNodeByCode(item.nodeCode());
            long eventId = repository.upsertEventByNaturalKey(
                    EventMerger.naturalKey(item.nodeCode(), item.eventType(), item.actor(), item.occurredOn()),
                    EventRow.draft(node.id(), item.eventType(), item.claimedLevel(), item.actor(),
                            item.occurredOn(), item.verificationLevel(), null, rubricVersionId));
            repository.markEventConfirmed(eventId);
            if (item.claimedLevel() != null && item.claimedLevel() > node.currentLevel()) {
                repository.advanceNode(node.id(), item.claimedLevel(), item.verificationLevel(),
                        eventId, rubricVersionId);
            }
        }
    }

    private void snapshotYearEnds(List<BackfillItem> items) {
        Params params = Params.defaults();
        List<NodeRow> nodes = repository.findAllNodes();
        Map<String, Integer> levels = new HashMap<>();
        int lastYear = LocalDate.now(ZoneOffset.UTC).getYear() - 1;
        int next = 0;
        for (int year = FIRST_SNAPSHOT_YEAR; year <= lastYear; year++) {
            while (next < items.size() && items.get(next).occurredOn().getYear() <= year) {
                BackfillItem item = items.get(next++);
                if (item.claimedLevel() != null) {
                    levels.merge(item.nodeCode(), item.claimedLevel(), Math::max);
                }
            }
            for (int pillar = 1; pillar <= 6; pillar++) {
                double readiness = pillarReadiness(nodes, levels, pillar, params);
                repository.insertPillarSnapshot(
                        pillar, LocalDate.of(year, 12, 31), readiness,
                        LogitEta.logitClipped(readiness, params.epsilon()), params.version());
            }
        }
    }

    private static double pillarReadiness(
            List<NodeRow> nodes, Map<String, Integer> levels, int pillar, Params params) {
        double readiness = 0;
        for (NodeRow node : nodes) {
            if (node.pillar() != pillar) {
                continue;
            }
            int level = levels.getOrDefault(node.code(), 0);
            if (level == 0) {
                continue;
            }
            Map<Integer, Double> mapping = "EGL".equals(node.scaleType())
                    ? params.maturityMap()
                    : params.trlMap();
            readiness += node.weight() * mapping.get(level);
        }
        return readiness;
    }

    private List<BackfillItem> readItems() {
        List<Map<String, Object>> raw;
        try {
            raw = JSON.readValue(
                    new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse tracker backfill resource " + resourcePath, e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load tracker backfill resource " + resourcePath, e);
        }
        List<BackfillItem> items = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            items.add(new BackfillItem(
                    (String) entry.get("nodeCode"),
                    (String) entry.get("eventType"),
                    entry.get("claimedLevel") instanceof Number number ? number.intValue() : null,
                    (String) entry.get("actor"),
                    LocalDate.parse((String) entry.get("occurredOn")),
                    (String) entry.get("verificationLevel"),
                    (String) entry.get("title")));
        }
        return items;
    }
}

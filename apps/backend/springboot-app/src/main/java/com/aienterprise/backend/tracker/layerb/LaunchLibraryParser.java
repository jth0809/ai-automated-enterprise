package com.aienterprise.backend.tracker.layerb;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses a Launch Library 2 launch page into {@link LaunchRecord}s. Pure and
 * dependency-light: malformed entries and invalid JSON are skipped rather than
 * throwing, so a feed hiccup never breaks ingestion.
 */
public class LaunchLibraryParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    public List<LaunchRecord> parse(String json) {
        return parsePage(json).map(LaunchPage::launches).orElseGet(List::of);
    }

    public Optional<LaunchPage> parsePage(String json) {
        List<LaunchRecord> result = new ArrayList<>();
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.path("results").isArray()) {
            return Optional.empty();
        }
        for (JsonNode item : root.path("results")) {
            if (!item.hasNonNull("id") || !item.hasNonNull("name") || !item.hasNonNull("net")) {
                continue;
            }
            Instant net;
            try {
                net = Instant.parse(item.get("net").asText());
            } catch (RuntimeException malformedDate) {
                continue;
            }
            String status = item.path("status").path("abbrev").asText("");
            boolean successful = "Success".equalsIgnoreCase(status)
                    || item.path("status").path("id").asInt(0) == 3;
            result.add(new LaunchRecord(
                    item.get("id").asText(),
                    item.get("name").asText(),
                    net,
                    item.path("launch_service_provider").path("name").asText(""),
                    status,
                    successful,
                    item.path("rocket").path("configuration")
                            .path("full_name").asText("")));
        }
        URI next = null;
        if (root.hasNonNull("next") && !root.get("next").asText().isBlank()) {
            try {
                next = URI.create(root.get("next").asText());
            } catch (IllegalArgumentException malformedNext) {
                return Optional.empty();
            }
        }
        return Optional.of(new LaunchPage(result, next));
    }
}
